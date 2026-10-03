package com.fileswitch.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

final class OcrTools {
    private OcrTools() {}

    static String imageText(ContentResolver resolver, Uri source, String type, String lang) throws Exception {
        if (!type.equals("svg")) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(source)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 20_000_000L) {
                throw new IOException("Image is too large or unreadable");
            }
        }
        Bitmap bitmap = ImageTools.decode(resolver, source, type);
        try {
            return recognize(bitmap, lang);
        } finally {
            bitmap.recycle();
        }
    }

    static String pdfText(ContentResolver resolver, Uri source, String lang) throws Exception {
        TextRecognizer recognizer = recognizer(lang);
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if (doc.getNumberOfPages() > 100) {
                throw new IOException("This PDF has more than 100 pages");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDRectangle page = doc.getPage(i).getMediaBox();
                double pixels = page.getWidth() / 72.0 * 180 * page.getHeight() / 72.0 * 180;
                if (pixels > 10_000_000) {
                    throw new IOException("A PDF page is too large for OCR on this phone");
                }
                Bitmap bitmap = renderer.renderImageWithDPI(i, 180, ImageType.RGB);
                try {
                    String pageText = recognize(bitmap, recognizer);
                    if (!pageText.trim().isEmpty()) {
                        if (text.length() > 0) text.append("\n\n");
                        text.append(pageText);
                    }
                } finally {
                    bitmap.recycle();
                }
            }
            return text.toString();
        } finally {
            recognizer.close();
        }
    }

    static File searchablePdf(ContentResolver resolver, Uri source, String type, String lang, File dir) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output directory");
        File out = new File(dir, UUID.randomUUID() + "-searchable.pdf");
        TextRecognizer recognizer = recognizer(lang);
        try (PDDocument doc = new PDDocument()) {
            if (type.equals("pdf")) {
                try (InputStream in = resolver.openInputStream(source); PDDocument srcDoc = PDDocument.load(in)) {
                    if (srcDoc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
                    if (srcDoc.getNumberOfPages() > 100) throw new IOException("This PDF has more than 100 pages");
                    PDFRenderer renderer = new PDFRenderer(srcDoc);
                    for (int i = 0; i < srcDoc.getNumberOfPages(); i++) {
                        Bitmap bitmap = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                        try {
                            addSearchablePage(doc, bitmap, recognizer);
                        } finally {
                            bitmap.recycle();
                        }
                    }
                }
            } else {
                Bitmap bitmap = ImageTools.decode(resolver, source, type);
                try {
                    addSearchablePage(doc, bitmap, recognizer);
                } finally {
                    bitmap.recycle();
                }
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.save(fos);
            }
            return out;
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            recognizer.close();
        }
    }

    private static void addSearchablePage(PDDocument doc, Bitmap bitmap, TextRecognizer recognizer) throws Exception {
        PDRectangle size = new PDRectangle(bitmap.getWidth(), bitmap.getHeight());
        PDPage page = new PDPage(size);
        doc.addPage(page);
        PDImageXObject image = JPEGFactory.createFromImage(doc, bitmap, 0.88f);
        Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
        try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
            stream.drawImage(image, 0, 0, bitmap.getWidth(), bitmap.getHeight());
            stream.setRenderingMode(RenderingMode.NEITHER);
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect box = line.getBoundingBox();
                    if (box != null && !line.getText().trim().isEmpty()) {
                        float x = box.left;
                        float y = bitmap.getHeight() - box.bottom;
                        float fontSize = Math.max(6, box.height() * 0.75f);
                        stream.beginText();
                        stream.setFont(PDType1Font.HELVETICA, fontSize);
                        stream.newLineAtOffset(x, y);
                        String safe = line.getText().replaceAll("[^\\x20-\\x7E]", " ");
                        if (!safe.trim().isEmpty()) {
                            stream.showText(safe);
                        }
                        stream.endText();
                    }
                }
            }
        }
    }

    private static String recognize(Bitmap bitmap, String lang) throws Exception {
        TextRecognizer recognizer = recognizer(lang);
        try {
            return recognize(bitmap, recognizer);
        } finally {
            recognizer.close();
        }
    }

    private static String recognize(Bitmap bitmap, TextRecognizer recognizer) throws Exception {
        return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
    }

    private static TextRecognizer recognizer(String lang) {
        if ("devanagari".equalsIgnoreCase(lang)) return TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
        if ("chinese".equalsIgnoreCase(lang)) return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        if ("japanese".equalsIgnoreCase(lang)) return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        if ("korean".equalsIgnoreCase(lang)) return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }
}
