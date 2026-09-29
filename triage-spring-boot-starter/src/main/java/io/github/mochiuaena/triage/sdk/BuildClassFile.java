package io.github.mochiuaena.triage.sdk;

import java.io.*;

/** Reads identity and SourceFile without loading or executing a project class. */
final class BuildClassFile {
    record Info(String name, String sourceFile) {}
    private BuildClassFile() {}
    static Info read(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xcafebabe) throw new IOException("Invalid class file");
            in.readUnsignedShort(); in.readUnsignedShort();
            int size = in.readUnsignedShort(); String[] text = new String[size]; int[] classes = new int[size];
            for (int i = 1; i < size; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> text[i] = in.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; }
                    case 7 -> classes[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("Invalid constant pool");
                }
            }
            in.readUnsignedShort(); int owner = in.readUnsignedShort(); in.readUnsignedShort();
            in.skipNBytes(in.readUnsignedShort() * 2L);
            for (int section = 0; section < 2; section++) {
                int members = in.readUnsignedShort();
                for (int i = 0; i < members; i++) { in.skipNBytes(6); attributes(in, text, false); }
            }
            String source = attributes(in, text, true);
            if (in.available() != 0 || owner <= 0 || owner >= size || classes[owner] <= 0 || classes[owner] >= size || text[classes[owner]] == null)
                throw new IOException("Invalid class identity");
            return new Info(text[classes[owner]].replace('/', '.'), source);
        } catch (IndexOutOfBoundsException e) { throw new IOException("Invalid class indexes"); }
    }
    private static String attributes(DataInputStream in, String[] text, boolean source) throws IOException {
        String result = null; int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int name = in.readUnsignedShort(); long length = Integer.toUnsignedLong(in.readInt());
            if (name <= 0 || name >= text.length || text[name] == null || length > in.available()) throw new IOException("Invalid class attribute");
            if (source && "SourceFile".equals(text[name])) {
                if (length != 2 || result != null) throw new IOException("Invalid source attribute");
                int index = in.readUnsignedShort();
                if (index <= 0 || index >= text.length || text[index] == null) throw new IOException("Invalid source name");
                result = text[index];
            } else in.skipNBytes(length);
        }
        return result;
    }
}
