package io.github.mochiuaena.triage.sdk;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SourceBuildManifestTest {
    @TempDir Path root;
    private Path sources() throws Exception { return Files.createDirectories(root.resolve("sources")); }
    private Path classes(String name) throws Exception { return Files.createDirectories(root.resolve(name)); }
    private Path compile(Path sources, Path classes, String content) throws Exception {
        Path file = sources.resolve("Probe.java"); Files.writeString(file, content);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", classes.toString(), file.toString())).isZero();
        return file;
    }
    private String code(String result) { return "package fixture; public class Probe { static { System.setProperty(\"triage-build-init-fixture\",\"executed\"); } public String lookup() { return \"" + result + "\"; } public static class Inner {} }"; }
    @Test void directoryAndJarMetadataBindTheCorrectClassBytesWithoutInitializingTheClass() throws Exception {
        Path sources = sources(), classes = classes("classes"); Path file = compile(sources, classes, code("one"));
        assertThat(SourceBuildManifest.generate(sources, classes)).isEqualTo(2);
        String expected = SourceBuildManifest.digest(Files.readAllBytes(file));
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            assertThat(SourceBuildVersions.sourceHash(loader.loadClass("fixture.Probe"))).isEqualTo(expected);
            assertThat(SourceBuildVersions.sourceHash(loader.loadClass("fixture.Probe$Inner"))).isEqualTo(expected);
        }
        Path jar = root.resolve("fixture.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path item : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(item).toString().replace('\\', '/'))); out.write(Files.readAllBytes(item)); out.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) { assertThat(SourceBuildVersions.sourceHash(loader.loadClass("fixture.Probe"))).isEqualTo(expected); }
        assertThat(System.getProperty("triage-build-init-fixture")).isNull();
        String metadata = Files.readString(classes.resolve(SourceBuildManifest.RESOURCE));
        assertThat(metadata).doesNotContain("lookup", "System.setProperty", root.toString(), "executed");
    }
    @Test void changedClassResourceMissingOrMalformedManifestRemainUnknown() throws Exception {
        Path sources = sources(), classes = classes("classes"); compile(sources, classes, code("old")); SourceBuildManifest.generate(sources, classes);
        compile(sources, classes, code("new"));
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) { assertThat(SourceBuildVersions.sourceHash(loader.loadClass("fixture.Probe"))).isNull(); }
        Files.delete(classes.resolve(SourceBuildManifest.RESOURCE));
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) { assertThat(SourceBuildVersions.sourceHash(loader.loadClass("fixture.Probe"))).isNull(); }
        Files.writeString(classes.resolve(SourceBuildManifest.RESOURCE), "format=1\nfixture.Probe=malformed\n");
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) { assertThat(SourceBuildVersions.sourceHash(loader.loadClass("fixture.Probe"))).isNull(); }
    }
    @Test void duplicateSourceNamesAreNotAssignedAnArbitraryDigestAndSameNameClassesKeepTheirOwnOrigin() throws Exception {
        Path sources = sources(), first = classes("first"), second = classes("second");
        Path one = compile(sources, first, code("first")); SourceBuildManifest.generate(sources, first); String firstHash = SourceBuildManifest.digest(Files.readAllBytes(one));
        Path two = compile(sources, second, code("second")); SourceBuildManifest.generate(sources, second); String secondHash = SourceBuildManifest.digest(Files.readAllBytes(two));
        try (var a = new URLClassLoader(new URL[]{first.toUri().toURL()}, null); var b = new URLClassLoader(new URL[]{second.toUri().toURL()}, null)) {
            assertThat(SourceBuildVersions.sourceHash(a.loadClass("fixture.Probe"))).isEqualTo(firstHash);
            assertThat(SourceBuildVersions.sourceHash(b.loadClass("fixture.Probe"))).isEqualTo(secondHash);
        }
        Path duplicate = Files.createDirectories(sources.resolve("other")).resolve("Probe.java"); Files.writeString(duplicate, code("duplicate"));
        assertThat(SourceBuildManifest.generate(sources, first)).isZero();
    }
    @Test void malformedAndOversizedBuildInputsAreRefusedAndSourceVersionChecksRequireV3() throws Exception {
        Path sources = sources(), classes = classes("classes"); compile(sources, classes, code("fixture"));
        Files.write(classes.resolve("broken.class"), new byte[]{1,2,3});
        assertThatThrownBy(() -> SourceBuildManifest.generate(sources, classes)).isInstanceOf(java.io.IOException.class);
        Files.delete(classes.resolve("broken.class"));
        Files.writeString(sources.resolve("large.java"), "x".repeat(256 * 1024 + 1));
        assertThatThrownBy(() -> SourceBuildManifest.generate(sources, classes)).isInstanceOf(java.io.IOException.class);
        var properties = ObservationRecorderTest.properties(); properties.setSourceVersionChecks(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setEndpointObservations(true); properties.validate();
    }
}
