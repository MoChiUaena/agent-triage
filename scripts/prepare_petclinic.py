#!/usr/bin/env python3
"""Add observation dependency and build digests to a clean, pinned Petclinic checkout."""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REVISION = '67643c4137eb75bfeb177b427f8459c471bdcbd8'

def prepare(directory):
    def git(*args):
        return subprocess.check_output(['git', '-C', str(directory), *args], text=True).strip()
    if git('rev-parse', 'HEAD') != REVISION:
        raise ValueError('Petclinic checkout must use the documented upstream revision')
    origin = git('config', '--get', 'remote.origin.url').removesuffix('.git').rstrip('/')
    if origin not in ('https://github.com/spring-projects/spring-petclinic', 'git@github.com:spring-projects/spring-petclinic'):
        raise ValueError('Checkout is not the documented public repository')
    if git('status', '--porcelain'):
        raise ValueError('Use a clean checkout; existing edits will not be overwritten')
    pom = directory / 'pom.xml'
    original = pom.read_bytes()
    text = original.decode('utf-8')
    newline = '\r\n' if '\r\n' in text else '\n'
    dependency = '''
    <dependency>
      <groupId>io.github.mochiuaena</groupId>
      <artifactId>triage-spring-boot-starter</artifactId>
      <version>0.4.0</version>
    </dependency>'''.replace('\n', newline)
    plugin = '''
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.6.3</version>
        <executions>
          <execution>
            <id>record-build-sources</id>
            <phase>process-classes</phase>
            <goals><goal>java</goal></goals>
            <configuration>
              <mainClass>io.github.mochiuaena.triage.sdk.SourceBuildManifest</mainClass>
              <arguments>
                <argument>${project.build.sourceDirectory}</argument>
                <argument>${project.build.outputDirectory}</argument>
              </arguments>
            </configuration>
          </execution>
        </executions>
      </plugin>'''.replace('\n', newline)
    if '  <dependencies>' not in text or '    <plugins>' not in text:
        raise ValueError('Expected upstream Maven sections are missing')
    text = text.replace('  <dependencies>', '  <dependencies>' + dependency, 1)
    text = text.replace('    <plugins>', '    <plugins>' + plugin, 1)
    backup = directory / 'target/triage-original-pom.xml'
    backup.parent.mkdir(exist_ok=True)
    backup.write_bytes(original)
    pom.write_bytes(text.encode('utf-8'))
    support = ROOT / 'verification/petclinic/PetclinicTriageConfiguration.java'
    destination = directory / 'src/main/java/org/springframework/samples/petclinic/triage/PetclinicTriageConfiguration.java'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(support.read_bytes())
    print('Added Starter, source build manifest and isolated observation wiring; business source files untouched')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    prepare(args.directory.resolve())
