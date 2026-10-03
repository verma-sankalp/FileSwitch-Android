package com.fileswitch.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;
import com.caverock.androidsvg.SVG;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

final class ImageTools {
    private ImageTools() {}

    static Bitmap decode(ContentResolver resolver, Uri uri, String type) throws Exception {
        if (!type.equals("svg")) {
            try (InputStream in = resolver.openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) throw new IllegalArgumentException("This phone cannot decode that image format");
                return bitmap;
            }
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            SVG svg = SVG.getFromInputStream(in);
            float width = svg.getDocumentWidth(), height = svg.getDocumentHeight();
            if (width <= 0 || height <= 0) {
                width = 1200;
                height = 1200;
            }
            if (width > 6000 || height > 6000 || width * height > 20_000_000f)
                throw new IllegalArgumentException("SVG is too large to render");
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width)), Math.max(1, Math.round(height)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.renderToCanvas(canvas);
            return bitmap;
        }
    }

    static Bitmap edit(Bitmap original, String operation) {
        if (operation.equals("None")) return original;
        if (operation.startsWith("Resize ")) {
            int percent = Integer.parseInt(operation.replaceAll("[^0-9]", ""));
            return Bitmap.createScaledBitmap(original, Math.max(1, original.getWidth() * percent / 100), Math.max(1, original.getHeight() * percent / 100), true);
        }
        if (operation.startsWith("Crop center")) {
            String value = operation.replaceAll("[^0-9]", "");
            float keep = value.isEmpty() ? .8f : Integer.parseInt(value) / 100f;
            int w = Math.max(1, (int) (original.getWidth() * keep)), h = Math.max(1, (int) (original.getHeight() * keep));
            return Bitmap.createBitmap(original, (original.getWidth() - w) / 2, (original.getHeight() - h) / 2, w, h);
        }
        if (operation.equals("Grayscale")) {
            Bitmap result = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            canvas.drawBitmap(original, 0, 0, paint);
            return result;
        }
        Matrix matrix = new Matrix();
        if (operation.equals("Rotate 90°")) matrix.setRotate(90, original.getWidth() / 2f, original.getHeight() / 2f);
        else if (operation.equals("Flip horizontal")) matrix.setScale(-1, 1, original.getWidth() / 2f, original.getHeight() / 2f);
        else if (operation.equals("Flip vertical")) matrix.setScale(1, -1, original.getWidth() / 2f, original.getHeight() / 2f);
        else return original;
        return Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
    }

    static Bitmap thumbnail(ContentResolver resolver, Uri uri, String type) throws Exception {
        if (type.equals("svg")) {
            try (InputStream in = resolver.openInputStream(uri)) {
                SVG svg = SVG.getFromInputStream(in);
                Bitmap thumb = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
                svg.renderToCanvas(new Canvas(thumb), new RectF(0, 0, 320, 240));
                return thumb;
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IllegalArgumentException("Image preview is unavailable");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 480 || bounds.outHeight / options.inSampleSize > 480)
            options.inSampleSize *= 2;
        try (InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    static void setDpi(File file, int dpi) throws IOException {
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_X_RESOLUTION, dpi + "/1");
        exif.setAttribute(ExifInterface.TAG_Y_RESOLUTION, dpi + "/1");
        exif.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2");
        exif.saveAttributes();
    }

    static void copyExif(ContentResolver resolver, Uri sourceUri, File destFile) {
        try (InputStream in = resolver.openInputStream(sourceUri)) {
            if (in == null) return;
            ExifInterface srcExif = new ExifInterface(in);
            ExifInterface dstExif = new ExifInterface(destFile.getAbsolutePath());
            String[] tags = {
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_FOCAL_LENGTH
            };
            boolean changed = false;
            for (String tag : tags) {
                String val = srcExif.getAttribute(tag);
                if (val != null) {
                    dstExif.setAttribute(tag, val);
                    changed = true;
                }
            }
            if (changed) dstExif.saveAttributes();
        } catch (Exception ignored) {}
    }

    static int readDpi(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int unit = exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0);
            double dpi = exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0);
            if (unit == 3) dpi *= 2.54;
            if (unit != 2 && unit != 3 || dpi < 1 || dpi > 2400) return 0;
            return (int) Math.round(dpi);
        }
    }

    static void scrubGpsExif(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            String[] gpsTags = {
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD
            };
            for (String tag : gpsTags) {
                exif.setAttribute(tag, null);
            }
            exif.saveAttributes();
        } catch (Exception ignored) {}
    }

    static void editExif(File file, String description, String artist, String copyright, String make, String model, boolean scrubGps) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            if (description != null && !description.isEmpty()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description);
            if (artist != null && !artist.isEmpty()) exif.setAttribute(ExifInterface.TAG_ARTIST, artist);
            if (copyright != null && !copyright.isEmpty()) exif.setAttribute(ExifInterface.TAG_COPYRIGHT, copyright);
            if (make != null && !make.isEmpty()) exif.setAttribute(ExifInterface.TAG_MAKE, make);
            if (model != null && !model.isEmpty()) exif.setAttribute(ExifInterface.TAG_MODEL, model);
            if (scrubGps) {
                String[] gpsTags = {
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD
                };
                for (String tag : gpsTags) exif.setAttribute(tag, null);
            }
            exif.saveAttributes();
        } catch (Exception ignored) {}
    }

    static File generateContactSheet(ContentResolver resolver, List<Uri> images, int cols, int rows, File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
        File out = new File(dir, UUID.randomUUID() + "-contact-sheet.png");
        int count = images.size();
        if (count == 0) throw new IOException("Select at least one image");
        int columns = Math.max(1, cols);
        int gridRows = Math.max(1, rows);
        int cellW = 320, cellH = 260;
        int padding = 20;
        int totalW = padding + columns * (cellW + padding);
        int totalH = padding + gridRows * (cellH + padding);

        Bitmap sheet = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(Color.WHITE);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.DKGRAY);
        textPaint.setTextSize(12);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.LTGRAY);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2);

        int index = 0;
        for (int r = 0; r < gridRows && index < count; r++) {
            for (int c = 0; c < columns && index < count; c++) {
                Uri uri = images.get(index);
                int x = padding + c * (cellW + padding);
                int y = padding + r * (cellH + padding);

                try {
                    Bitmap thumb = thumbnail(resolver, uri, "jpg");
                    if (thumb != null) {
                        float scale = Math.min((cellW - 10f) / thumb.getWidth(), (cellH - 40f) / thumb.getHeight());
                        int dw = Math.round(thumb.getWidth() * scale);
                        int dh = Math.round(thumb.getHeight() * scale);
                        int dx = x + (cellW - dw) / 2;
                        int dy = y + (cellH - 30 - dh) / 2;

                        canvas.drawRect(x, y, x + cellW, y + cellH, borderPaint);
                        canvas.drawBitmap(thumb, null, new Rect(dx, dy, dx + dw, dy + dh), new Paint(Paint.FILTER_BITMAP_FLAG));
                        thumb.recycle();
                    }
                } catch (Exception ignored) {}

                String label = "Image " + (index + 1);
                canvas.drawText(label, x + cellW / 2f, y + cellH - 10, textPaint);
                index++;
            }
        }

        try (FileOutputStream fos = new FileOutputStream(out)) {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } finally {
            sheet.recycle();
        }
        return out;
    }
}
