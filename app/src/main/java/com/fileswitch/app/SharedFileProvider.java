package com.fileswitch.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class SharedFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment == null) return "text/plain";
        int dot = lastSegment.lastIndexOf('.');
        String extension = (dot >= 0 && dot < lastSegment.length() - 1) ? lastSegment.substring(dot + 1).toLowerCase() : "";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (type != null && !type.isEmpty() && !type.equalsIgnoreCase("application/octet-stream")) {
            return type;
        }
        switch (extension) {
            case "pdf": return "application/pdf";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc": return "application/msword";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls": return "application/vnd.ms-excel";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            case "rtf": return "application/rtf";
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "html":
            case "htm": return "text/html";
            case "md": return "text/markdown";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "zip": return "application/zip";
            default: return "text/plain";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (IOException error) {
            return null;
        }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = file.getName();
            if (OpenableColumns.SIZE.equals(columns[index])) row[index] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Shared files are read-only.");
        try {
            return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) {
            throw new FileNotFoundException("Shared file is unavailable.");
        }
    }

    private File resolve(Uri uri) throws IOException {
        String segment = uri.getLastPathSegment();
        if (segment == null) throw new FileNotFoundException("Invalid URI segment");

        // 1. Check cache directory
        File cacheRoot = new File(getContext().getCacheDir(), "fileswitch").getCanonicalFile();
        File cacheFile = new File(cacheRoot, segment).getCanonicalFile();
        if (cacheFile.getPath().startsWith(cacheRoot.getPath() + File.separator) && cacheFile.isFile() && cacheFile.exists()) {
            return cacheFile;
        }

        // 2. Check Documents/FileSwitch
        try {
            File docDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS);
            if (docDir != null) {
                File docRoot = new File(docDir, "FileSwitch").getCanonicalFile();
                File docFile = new File(docRoot, segment).getCanonicalFile();
                if (docFile.getPath().startsWith(docRoot.getPath() + File.separator) && docFile.isFile() && docFile.exists()) {
                    return docFile;
                }
            }
        } catch (Exception ignored) {}

        // 3. Check Download/FileSwitch
        try {
            File dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dlDir != null) {
                File dlRoot = new File(dlDir, "FileSwitch").getCanonicalFile();
                File dlFile = new File(dlRoot, segment).getCanonicalFile();
                if (dlFile.getPath().startsWith(dlRoot.getPath() + File.separator) && dlFile.isFile() && dlFile.exists()) {
                    return dlFile;
                }
            }
        } catch (Exception ignored) {}

        // 4. Check External Storage Root/FileSwitch
        try {
            File extRoot = new File(android.os.Environment.getExternalStorageDirectory(), "FileSwitch").getCanonicalFile();
            File extFile = new File(extRoot, segment).getCanonicalFile();
            if (extFile.getPath().startsWith(extRoot.getPath() + File.separator) && extFile.isFile() && extFile.exists()) {
                return extFile;
            }
        } catch (Exception ignored) {}

        // 5. Check App External Files Dir/FileSwitch
        try {
            File appExt = getContext().getExternalFilesDir("FileSwitch");
            if (appExt != null) {
                File appExtRoot = appExt.getCanonicalFile();
                File appExtFile = new File(appExtRoot, segment).getCanonicalFile();
                if (appExtFile.getPath().startsWith(appExtRoot.getPath() + File.separator) && appExtFile.isFile() && appExtFile.exists()) {
                    return appExtFile;
                }
            }
        } catch (Exception ignored) {}

        if (cacheFile.isFile() && cacheFile.exists()) return cacheFile;
        throw new FileNotFoundException("Shared file is unavailable: " + segment);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("Shared files are read-only.");
    }
}
