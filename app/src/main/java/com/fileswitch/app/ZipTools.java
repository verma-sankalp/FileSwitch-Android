package com.fileswitch.app;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ZipTools {
    static final class Entry {
        final File file;
        final String name, mime;

        Entry(File f, String n, String m) {
            file = f;
            name = n;
            mime = m;
        }
    }

    private ZipTools() {}

    static ArrayList<Entry> extract(ContentResolver resolver, Uri source, File dir) throws IOException {
        String format = detectArchiveFormat(resolver, source);
        if ("gz".equals(format) || "tgz".equals(format)) {
            return extractGzipOrTar(resolver, source, dir, "tgz".equals(format));
        } else if ("tar".equals(format)) {
            try (InputStream in = resolver.openInputStream(source)) {
                if (in == null) throw new IOException("Archive could not be opened");
                return extractTar(in, dir);
            }
        }
        return extractZip(resolver, source, dir);
    }

    private static String detectArchiveFormat(ContentResolver resolver, Uri source) {
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) return "zip";
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) {
                try (InputStream checkIn = resolver.openInputStream(source);
                     GZIPInputStream gz = new GZIPInputStream(checkIn)) {
                    byte[] tarHdr = new byte[512];
                    int gn = gz.read(tarHdr);
                    if (gn >= 262 && new String(tarHdr, 257, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("ustar")) {
                        return "tgz";
                    }
                } catch (Exception ignored) {}
                return "gz";
            }
            if (n >= 262 && new String(b, 257, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("ustar")) {
                return "tar";
            }
        } catch (Exception ignored) {}
        return "zip";
    }

    static ArrayList<Entry> extractZip(ContentResolver resolver, Uri source, File dir) throws IOException {
        ArrayList<Entry> files = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        long total = 0;
        try (InputStream raw = resolver.openInputStream(source); ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 1000) throw new IOException("This archive has too many entries");
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String original = entry.getName().replace('\\', '/');
                if (original.startsWith("/") || original.matches("^[A-Za-z]:.*") || containsParent(original))
                    throw new IOException("This archive contains an unsafe path");
                String base = original.substring(original.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
                if (base.isEmpty()) continue;
                String name = base;
                int i = 2;
                while (!names.add(name)) {
                    int dot = base.lastIndexOf('.');
                    name = dot > 0 ? base.substring(0, dot) + " (" + i++ + ")" + base.substring(dot) : base + " (" + i++ + ")";
                }
                File out = new File(dir, UUID.randomUUID() + "-" + name);
                long item = 0;
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    byte[] b = new byte[32768];
                    int n;
                    while ((n = zip.read(b)) > 0) {
                        item += n;
                        total += n;
                        if (item > 100L * 1024 * 1024 || total > 500L * 1024 * 1024)
                            throw new IOException("Extracted files exceed the 500 MB limit");
                        stream.write(b, 0, n);
                    }
                } catch (IOException e) {
                    out.delete();
                    throw e;
                }
                files.add(new Entry(out, name, mime(name)));
                zip.closeEntry();
            }
        } catch (IOException e) {
            for (Entry entry : files) entry.file.delete();
            throw e;
        }
        if (files.isEmpty()) throw new IOException("This archive contains no readable files");
        return files;
    }

    private static ArrayList<Entry> extractGzipOrTar(ContentResolver resolver, Uri source, File dir, boolean isTarGz) throws IOException {
        try (InputStream in = resolver.openInputStream(source); GZIPInputStream gz = new GZIPInputStream(in)) {
            if (isTarGz) {
                return extractTar(gz, dir);
            }
            ArrayList<Entry> files = new ArrayList<>();
            File tempFile = new File(dir, UUID.randomUUID() + "-temp.tmp");
            byte[] header = new byte[32];
            int headerLen = 0;
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] b = new byte[32768];
                int n;
                while ((n = gz.read(b)) > 0) {
                    if (headerLen < header.length) {
                        int copy = Math.min(n, header.length - headerLen);
                        System.arraycopy(b, 0, header, headerLen, copy);
                        headerLen += copy;
                    }
                    fos.write(b, 0, n);
                }
            } catch (IOException e) {
                tempFile.delete();
                throw e;
            }

            String ext = "txt";
            String mimeType = "text/plain";
            if (headerLen >= 4 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F') {
                ext = "pdf";
                mimeType = "application/pdf";
            } else if (headerLen >= 4 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
                ext = "png";
                mimeType = "image/png";
            } else if (headerLen >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
                ext = "jpg";
                mimeType = "image/jpeg";
            } else if (headerLen >= 4 && header[0] == 'P' && header[1] == 'K' && (header[2] == 3 || header[2] == 5) && (header[3] == 4 || header[3] == 6)) {
                ext = "zip";
                mimeType = "application/zip";
            } else {
                boolean isText = true;
                for (int i = 0; i < headerLen; i++) {
                    int c = header[i] & 0xFF;
                    if (c < 9 || (c > 13 && c < 32)) {
                        isText = false;
                        break;
                    }
                }
                if (isText && headerLen > 0) {
                    ext = "txt";
                    mimeType = "text/plain";
                }
            }

            String finalName = "extracted-file." + ext;
            File out = new File(dir, UUID.randomUUID() + "-" + finalName);
            if (!tempFile.renameTo(out)) {
                out = tempFile;
            }
            files.add(new Entry(out, finalName, mimeType));
            return files;
        }
    }

    private static ArrayList<Entry> extractTar(InputStream in, File dir) throws IOException {
        ArrayList<Entry> files = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        byte[] header = new byte[512];
        while (true) {
            int read = readFully(in, header);
            if (read < 512 || isAllZeros(header)) break;
            String rawName = new String(header, 0, 100, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (rawName.isEmpty()) break;
            rawName = rawName.replace('\\', '/');
            if (rawName.startsWith("/") || containsParent(rawName)) throw new IOException("Unsafe tar path detected");
            String sizeStr = new String(header, 124, 11, java.nio.charset.StandardCharsets.US_ASCII).trim();
            long size = 0;
            try {
                size = Long.parseLong(sizeStr, 8);
            } catch (Exception ignored) {}
            byte typeFlag = header[156];
            if (typeFlag == '5' || rawName.endsWith("/")) {
                skipBytes(in, (size + 511) / 512 * 512);
                continue;
            }
            String base = rawName.substring(rawName.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
            if (base.isEmpty()) base = "file";
            String name = base;
            int suffix = 2;
            while (!names.add(name)) {
                int dot = base.lastIndexOf('.');
                name = dot > 0 ? base.substring(0, dot) + " (" + suffix++ + ")" + base.substring(dot) : base + " (" + suffix++ + ")";
            }
            File out = new File(dir, UUID.randomUUID() + "-" + name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32768];
                long remaining = size;
                while (remaining > 0) {
                    int chunk = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, chunk);
                    if (n <= 0) break;
                    fos.write(buf, 0, n);
                    remaining -= n;
                }
            } catch (IOException e) {
                out.delete();
                throw e;
            }
            long padding = (512 - (size % 512)) % 512;
            if (padding > 0) skipBytes(in, padding);
            files.add(new Entry(out, name, mime(name)));
        }
        if (files.isEmpty()) throw new IOException("This tar archive contains no readable files");
        return files;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int total = 0;
        while (total < b.length) {
            int n = in.read(b, total, b.length - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    private static void skipBytes(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) break;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static boolean isAllZeros(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static boolean containsParent(String path) {
        for (String part : path.split("/")) if (part.equals("..")) return true;
        return false;
    }

    private static String mime(String name) {
        if (name == null || !name.contains(".")) return "text/plain";
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mapped = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mapped != null && !mapped.isEmpty() && !mapped.equalsIgnoreCase("application/octet-stream")) {
            return mapped;
        }
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt":
            case "csv":
            case "md":
            case "json":
            case "xml":
            case "html":
            case "htm":
                return "text/plain";
            case "zip":
                return "application/zip";
            default:
                return "text/plain";
        }
    }
}
