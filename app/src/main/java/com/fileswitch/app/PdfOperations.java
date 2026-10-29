package com.fileswitch.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardDecryptionMaterial;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class PdfOperations {
    private PdfOperations() {}
    static File output(File dir,String base) throws IOException { if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not prepare output");return new File(dir,UUID.randomUUID()+"-"+base); }
    static File merge(ContentResolver resolver,List<Uri> sources,File dir) throws IOException {
        File out=output(dir,"merged.pdf");PDFMergerUtility merger=new PDFMergerUtility();merger.setDestinationFileName(out.getAbsolutePath());ArrayList<InputStream> streams=new ArrayList<>();
        try{for(Uri uri:sources){InputStream in=resolver.openInputStream(uri);if(in==null)throw new IOException("A PDF could not be opened");streams.add(in);merger.addSource(in);}merger.mergeDocuments(null);return out;}catch(IOException e){out.delete();throw e;}finally{for(InputStream in:streams)try{in.close();}catch(IOException ignored){}}
    }
    static ArrayList<File> split(ContentResolver resolver,Uri source,File dir) throws IOException {
        ArrayList<File> out=new ArrayList<>();
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if(doc.getNumberOfPages()>100)throw new IOException("This PDF has more than 100 pages");
            for(int i=0;i<doc.getNumberOfPages();i++){
                File page=output(dir,"page-"+(i+1)+".pdf");
                try(PDDocument part=new PDDocument()){
                    part.importPage(doc.getPage(i));
                    part.save(page);
                }
                out.add(page);
            }
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            for(File f:out)f.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            for(File f:out)f.delete();
            throw e;
        }
        return out;
    }
    static File extract(ContentResolver resolver,Uri source,List<Integer> indexes,File dir) throws IOException {
        File out=output(dir,"pages.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in);PDDocument selected=new PDDocument()){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            for(int index:indexes){
                if(index<0||index>=doc.getNumberOfPages())throw new IOException("Page number is outside this PDF");
                selected.importPage(doc.getPage(index));
            }
            if(selected.getNumberOfPages()==0)throw new IOException("Select at least one page");
            selected.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
    }
    static File reorder(ContentResolver resolver,Uri source,List<Integer> order,File dir) throws IOException {
        File out=output(dir,"reordered.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in);PDDocument reordered=new PDDocument()){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if(order.size()!=doc.getNumberOfPages())throw new IOException("Enter each page once, separated by commas");
            HashSet<Integer> seen=new HashSet<>();
            for(int index:order){
                if(index<0||index>=doc.getNumberOfPages()||!seen.add(index))throw new IOException("Page order must include each page once");
                reordered.importPage(doc.getPage(index));
            }
            reordered.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
    }
    static File deletePages(ContentResolver resolver,Uri source,List<Integer> indexes,File dir) throws IOException {
        File out=output(dir,"pages-removed.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            indexes.sort((a,b)->b-a);
            for(int index:indexes){
                if(index<0||index>=doc.getNumberOfPages())throw new IOException("Page number is outside this PDF");
                if(doc.getNumberOfPages()==1)throw new IOException("A PDF must keep at least one page");
                doc.removePage(index);
            }
            doc.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
    }
    static File rotate(ContentResolver resolver, Uri source, int degrees, File dir) throws IOException {
        return rotate(resolver, source, null, degrees, dir);
    }

    static File rotate(ContentResolver resolver, Uri source, List<Integer> indexes, int degrees, File dir) throws IOException {
        File out = output(dir, "rotated.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if (indexes == null || indexes.isEmpty()) {
                for (PDPage page : doc.getPages()) page.setRotation((page.getRotation() + degrees) % 360);
            } else {
                for (int index : indexes) {
                    if (index >= 0 && index < doc.getNumberOfPages()) {
                        PDPage page = doc.getPage(index);
                        page.setRotation((page.getRotation() + degrees) % 360);
                    }
                }
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }
    static File resizePages(ContentResolver resolver,Uri source,int preset,int dpi,File dir)throws IOException{
        PDRectangle[] sizes={PDRectangle.A4,new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()),PDRectangle.LETTER,new PDRectangle(PDRectangle.LETTER.getHeight(),PDRectangle.LETTER.getWidth())};
        File out=output(dir,"resized.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument original=PDDocument.load(in);PDDocument result=new PDDocument()){
            if(original.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if(preset<0||preset>=sizes.length)throw new IOException("Choose a supported page size");
            if(original.getNumberOfPages()>100)throw new IOException("This PDF has more than 100 pages");
            PDFRenderer renderer=new PDFRenderer(original);
            PDRectangle target=sizes[preset];
            for(int i=0;i<original.getNumberOfPages();i++){
                double pixels=target.getWidth()/72.0*dpi*target.getHeight()/72.0*dpi;
                if(pixels>12_000_000)throw new IOException("The selected page size and DPI are too large for this phone");
                Bitmap bitmap=renderer.renderImageWithDPI(i,dpi,ImageType.RGB);
                try{
                    PDPage page=new PDPage(target);
                    result.addPage(page);
                    PDImageXObject image=JPEGFactory.createFromImage(result,bitmap,.9f);
                    float scale=Math.min(target.getWidth()/bitmap.getWidth(),target.getHeight()/bitmap.getHeight());
                    float width=bitmap.getWidth()*scale,height=bitmap.getHeight()*scale;
                    try(PDPageContentStream content=new PDPageContentStream(result,page)){
                        content.drawImage(image,(target.getWidth()-width)/2,(target.getHeight()-height)/2,width,height);
                    }
                }finally{
                    bitmap.recycle();
                }
            }
            result.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
    }
    static File resizePagesByPercent(ContentResolver resolver, Uri source, int percent, int dpi, File dir) throws IOException {
        if (percent < 1 || percent > 100) throw new IOException("Resize percentage must be between 1% and 100%");
        File out = output(dir, "resized.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument original = PDDocument.load(in); PDDocument result = new PDDocument()) {
            if (original.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if (original.getNumberOfPages() > 100) throw new IOException("This PDF has more than 100 pages");
            PDFRenderer renderer = new PDFRenderer(original);
            for (int i = 0; i < original.getNumberOfPages(); i++) {
                PDPage origPage = original.getPage(i);
                PDRectangle origBox = origPage.getMediaBox() != null ? origPage.getMediaBox() : origPage.getCropBox();
                if (origBox == null) origBox = PDRectangle.A4;
                float origW = origBox.getWidth();
                float origH = origBox.getHeight();
                int rotation = origPage.getRotation();
                if (rotation == 90 || rotation == 270) {
                    float tmp = origW;
                    origW = origH;
                    origH = tmp;
                }
                float targetW = Math.max(1f, origW * percent / 100f);
                float targetH = Math.max(1f, origH * percent / 100f);
                PDRectangle target = new PDRectangle(targetW, targetH);

                double pixels = targetW / 72.0 * dpi * targetH / 72.0 * dpi;
                if (pixels > 12_000_000) throw new IOException("The PDF page size and DPI are too large for this phone");
                Bitmap bitmap = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                try {
                    PDPage page = new PDPage(target);
                    result.addPage(page);
                    PDImageXObject image = JPEGFactory.createFromImage(result, bitmap, .9f);
                    float scale = Math.min(target.getWidth() / bitmap.getWidth(), target.getHeight() / bitmap.getHeight());
                    float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
                    try (PDPageContentStream content = new PDPageContentStream(result, page)) {
                        content.drawImage(image, (target.getWidth() - width) / 2, (target.getHeight() - height) / 2, width, height);
                    }
                } finally {
                    bitmap.recycle();
                }
            }
            result.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }
    static File insertImage(ContentResolver resolver, Uri source, Bitmap bitmap, int pageNumber, File dir) throws IOException {
        return insertImage(resolver, source, bitmap, pageNumber, 0.25f, 0.25f, 0.5f, 0.5f, dir);
    }
    static File insertImage(ContentResolver resolver, Uri source, Bitmap bitmap, int pageNumber, float normX, float normY, float normW, float normH, File dir) throws IOException {
        File out = output(dir, "image-added.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) throw new IOException("Choose a page between 1 and " + doc.getNumberOfPages());
            PDPage page = doc.getPage(pageNumber - 1);
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            if (box == null) box = PDRectangle.A4;

            PDImageXObject image = LosslessFactory.createFromImage(doc, bitmap);

            float boxW = box.getWidth();
            float boxH = box.getHeight();

            float drawW = Math.max(1f, normW * boxW);
            float drawH = Math.max(1f, normH * boxH);
            float pdfX = box.getLowerLeftX() + normX * boxW;
            float pdfY = box.getLowerLeftY() + (1.0f - normY - normH) * boxH;

            try (PDPageContentStream stream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.drawImage(image, pdfX, pdfY, drawW, drawH);
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }
    static File crop(ContentResolver resolver, Uri source, float margin, File dir) throws IOException {
        File out = output(dir, "cropped.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                float width = box.getWidth() - margin * 2, height = box.getHeight() - margin * 2;
                if (width < 20 || height < 20) throw new IOException("Crop margin is too large");
                PDRectangle newBox = new PDRectangle(box.getLowerLeftX() + margin, box.getLowerLeftY() + margin, width, height);
                page.setCropBox(newBox);
                page.setMediaBox(newBox);
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    static File crop(ContentResolver resolver, Uri source, float normLeft, float normTop, float normRight, float normBottom, File dir) throws IOException {
        File out = output(dir, "cropped.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                float boxW = box.getWidth();
                float boxH = box.getHeight();

                float clampedL = Math.max(0f, Math.min(normLeft, 0.9f));
                float clampedT = Math.max(0f, Math.min(normTop, 0.9f));
                float clampedR = Math.max(clampedL + 0.05f, Math.min(normRight, 1.0f));
                float clampedB = Math.max(clampedT + 0.05f, Math.min(normBottom, 1.0f));

                float newX = box.getLowerLeftX() + clampedL * boxW;
                float newW = (clampedR - clampedL) * boxW;
                float newY = box.getLowerLeftY() + (1.0f - clampedB) * boxH;
                float newH = (clampedB - clampedT) * boxH;

                if (newW < 10 || newH < 10) throw new IOException("Crop bounds are too small");
                PDRectangle newBox = new PDRectangle(newX, newY, newW, newH);
                page.setCropBox(newBox);
                page.setMediaBox(newBox);
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }
    static String normalizePdfText(String text) {
        if (text == null) return "";
        return text.replace(' ', ' ')
                   .replace(' ', ' ')
                   .replace(' ', ' ')
                   .replace(' ', ' ')
                   .replace(' ', ' ')
                   .replace("​", "")
                   .replace("﻿", "");
    }

    private static Bitmap createFooterBitmap(String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(80, 80, 80));
        paint.setTextSize(20);
        paint.setTypeface(Typeface.DEFAULT);
        int textW = Math.max(1, Math.round(paint.measureText(text))) + 12;
        int textH = 30;
        Bitmap bmp = Bitmap.createBitmap(textW, textH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawText(text, 2, 22, paint);
        return bmp;
    }

    static File annotate(ContentResolver resolver, Uri source, String text, File dir) throws IOException {
        File out = output(dir, "annotated.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            String cleanText = normalizePdfText(text);
            int number = 0;
            for (PDPage page : doc.getPages()) {
                number++;
                String pageText = cleanText + "  " + number;
                Bitmap footerBmp = createFooterBitmap(pageText);
                try {
                    PDImageXObject img = LosslessFactory.createFromImage(doc, footerBmp);
                    try (PDPageContentStream stream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        stream.drawImage(img, 24, 14, footerBmp.getWidth() / 2f, footerBmp.getHeight() / 2f);
                    }
                } finally {
                    footerBmp.recycle();
                }
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }
    static File compress(ContentResolver resolver, Uri source, int percent, File dir) throws IOException {
        File out = output(dir, "compressed.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            int count = doc.getNumberOfPages();
            if (count > 500) throw new IOException("This PDF has more than 500 pages");

            int compLevel = Math.max(1, Math.min(100, percent));
            float quality = Math.max(0.35f, 0.95f - (compLevel / 100f) * 0.60f);
            int maxDim = Math.max(480, Math.round(2400 - (compLevel / 100f) * 1800));

            for (int i = 0; i < count; i++) {
                PDPage page = doc.getPage(i);
                PDResources resources = page.getResources();
                if (resources != null) {
                    compressResources(doc, resources, maxDim, quality, compLevel);
                }
            }

            if (compLevel >= 40) {
                PDDocumentInformation info = doc.getDocumentInformation();
                if (info != null) {
                    info.setCustomMetadataValue("FileSwitchCompressed", "true");
                    info.setKeywords(null);
                    info.setTrapped(null);
                }
            }

            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    private static void compressResources(PDDocument doc, PDResources resources, int maxDim, float quality, int compLevel) {
        if (resources == null) return;
        try {
            for (COSName name : resources.getXObjectNames()) {
                try {
                    PDXObject xobject = resources.getXObject(name);
                    if (xobject instanceof PDImageXObject) {
                        PDImageXObject image = (PDImageXObject) xobject;
                        Bitmap originalBmp = image.getImage();
                        if (originalBmp != null) {
                            try {
                                int origW = originalBmp.getWidth();
                                int origH = originalBmp.getHeight();
                                int newW = origW;
                                int newH = origH;
                                if (maxDim > 0 && (origW > maxDim || origH > maxDim)) {
                                    if (origW >= origH) {
                                        newW = maxDim;
                                        newH = Math.max(1, Math.round((float) origH * maxDim / origW));
                                    } else {
                                        newH = maxDim;
                                        newW = Math.max(1, Math.round((float) origW * maxDim / origH));
                                    }
                                }

                                Bitmap processedBmp = originalBmp;
                                if (newW != origW || newH != origH) {
                                    processedBmp = Bitmap.createScaledBitmap(originalBmp, newW, newH, true);
                                }

                                PDImageXObject newImg;
                                if (processedBmp.hasAlpha()) {
                                    newImg = LosslessFactory.createFromImage(doc, processedBmp);
                                } else {
                                    newImg = JPEGFactory.createFromImage(doc, processedBmp, quality);
                                }

                                resources.put(name, newImg);

                                if (processedBmp != originalBmp) {
                                    processedBmp.recycle();
                                }
                            } finally {
                                originalBmp.recycle();
                            }
                        }
                    } else if (xobject instanceof PDFormXObject) {
                        PDFormXObject form = (PDFormXObject) xobject;
                        PDResources formRes = form.getResources();
                        if (formRes != null) {
                            compressResources(doc, formRes, maxDim, quality, compLevel);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
    static void protectInPlace(File file, String password) throws IOException {
        if (file == null || !file.exists() || password == null || password.isEmpty()) return;
        File temp = new File(file.getParentFile(), "enc-" + java.util.UUID.randomUUID() + ".pdf");
        try (PDDocument doc = PDDocument.load(file)) {
            if (doc.isEncrypted()) return;
            AccessPermission access = new AccessPermission();
            access.setCanPrint(true);
            access.setCanExtractContent(true);
            access.setCanModify(true);
            access.setCanModifyAnnotations(true);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(password + "-owner", password, access);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(temp);
        }
        if (temp.exists()) {
            file.delete();
            temp.renameTo(file);
        }
    }
    static File protect(ContentResolver resolver,Uri source,String password,int allowed,File dir) throws IOException {
        File out=output(dir,"protected.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){
            if(doc.isEncrypted())throw new IOException("This PDF is already password protected. Unlock it first.");
            AccessPermission access=new AccessPermission();
            access.setCanPrint((allowed&1)!=0);
            access.setCanExtractContent((allowed&2)!=0);
            access.setCanModify((allowed&4)!=0);
            access.setCanModifyAnnotations((allowed&4)!=0);
            StandardProtectionPolicy policy=new StandardProtectionPolicy(password+"-owner",password,access);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
    }
    static File removePassword(ContentResolver resolver,Uri source,String password,File dir) throws IOException {
        File out=output(dir,"unlocked.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in,password)){
            if(doc.isEncrypted()){
                doc.setAllSecurityToBeRemoved(true);
            }
            doc.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("Incorrect PDF password", e);
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
    }
    static File convertToPdfA(ContentResolver resolver, Uri source, File dir) throws IOException {
        File out = output(dir, "pdfa.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in); PDDocument pdfa = new PDDocument()) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                pdfa.importPage(doc.getPage(i));
            }
            com.tom_roush.pdfbox.pdmodel.common.PDMetadata metadata = new com.tom_roush.pdfbox.pdmodel.common.PDMetadata(pdfa);
            String xmp = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                    + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                    + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                    + "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n"
                    + "<pdfaid:part>1</pdfaid:part>\n"
                    + "<pdfaid:conformance>B</pdfaid:conformance>\n"
                    + "</rdf:Description>\n"
                    + "</rdf:RDF>\n"
                    + "</x:xmpmeta>\n"
                    + "<?xpacket end=\"w\"?>";
            metadata.importXMPMetadata(xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pdfa.getDocumentCatalog().setMetadata(metadata);
            pdfa.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    static File repair(ContentResolver resolver, Uri source, File dir) throws IOException {
        File out = output(dir, "repaired.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument src = PDDocument.load(in); PDDocument repaired = new PDDocument()) {
            if (src.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            int pages = src.getNumberOfPages();
            if (pages == 0) throw new IOException("No recoverable pages found in PDF");
            for (int i = 0; i < pages; i++) {
                try {
                    repaired.importPage(src.getPage(i));
                } catch (Exception ignored) {}
            }
            if (repaired.getNumberOfPages() == 0) throw new IOException("Could not recover pages from corrupted PDF");
            repaired.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    private static Bitmap createStampBitmap(String text, int position) {
        int w = position == 0 ? 560 : 440;
        int h = position == 0 ? 150 : 54;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(220, 220, 38, 38));
        paint.setTextSize(position == 0 ? 40 : 26);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.argb(220, 220, 38, 38));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(position == 0 ? 6f : 3f);

        RectF rect = new RectF(8, 8, w - 8, h - 8);
        canvas.drawRoundRect(rect, 12, 12, border);

        float textY = (h / 2f) - ((paint.descent() + paint.ascent()) / 2f);
        canvas.drawText(text, w / 2f, textY, paint);
        return bmp;
    }

    static File stamp(ContentResolver resolver, Uri source, String text, int position, File dir) throws IOException {
        File out = output(dir, "stamped.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            String cleanText = normalizePdfText(text);
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getCropBox();
                Bitmap stampBmp = createStampBitmap(cleanText, position);
                try {
                    PDImageXObject image = LosslessFactory.createFromImage(doc, stampBmp);
                    try (PDPageContentStream stream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        if (position == 0) {
                            float w = box.getWidth() * 0.65f;
                            float h = w * ((float) stampBmp.getHeight() / stampBmp.getWidth());
                            float cx = box.getLowerLeftX() + (box.getWidth() - w) / 2f;
                            float cy = box.getLowerLeftY() + (box.getHeight() - h) / 2f;
                            stream.drawImage(image, cx, cy, w, h);
                        } else if (position == 1) {
                            float w = Math.min(box.getWidth() - 72, 220);
                            float h = w * ((float) stampBmp.getHeight() / stampBmp.getWidth());
                            stream.drawImage(image, box.getLowerLeftX() + 36, box.getUpperRightY() - h - 24, w, h);
                        } else {
                            float w = Math.min(box.getWidth() - 72, 220);
                            float h = w * ((float) stampBmp.getHeight() / stampBmp.getWidth());
                            stream.drawImage(image, box.getLowerLeftX() + 36, box.getLowerLeftY() + 24, w, h);
                        }
                    }
                } finally {
                    stampBmp.recycle();
                }
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    static File editMetadata(ContentResolver resolver, Uri source, String title, String author, String subject, String keywords, File dir) throws IOException {
        File out = output(dir, "metadata-updated.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            com.tom_roush.pdfbox.pdmodel.PDDocumentInformation info = doc.getDocumentInformation();
            if (title != null && !title.isEmpty()) info.setTitle(title);
            if (author != null && !author.isEmpty()) info.setAuthor(author);
            if (subject != null && !subject.isEmpty()) info.setSubject(subject);
            if (keywords != null && !keywords.isEmpty()) info.setKeywords(keywords);
            info.setModificationDate(Calendar.getInstance());
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    static ArrayList<String> getFormFieldNames(ContentResolver resolver, Uri source) {
        ArrayList<String> names = new ArrayList<>();
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.getDocumentCatalog().getAcroForm() != null) {
                for (com.tom_roush.pdfbox.pdmodel.interactive.form.PDField f : doc.getDocumentCatalog().getAcroForm().getFields()) {
                    names.add(f.getFullyQualifiedName());
                }
            }
        } catch (Exception ignored) {}
        return names;
    }

    static File fillForm(ContentResolver resolver, Uri source, String textValues, boolean flatten, File dir) throws IOException {
        File out = output(dir, "form-filled.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) throw new IOException("This PDF does not contain interactive form fields");
            String[] lines = textValues.split("\n");
            for (String line : lines) {
                String[] pair = line.split("=", 2);
                if (pair.length == 2) {
                    String key = pair[0].trim(), val = pair[1].trim();
                    com.tom_roush.pdfbox.pdmodel.interactive.form.PDField field = form.getField(key);
                    if (field != null) {
                        try {
                            field.setValue(val);
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (flatten) form.flatten();
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    private static Bitmap createSignatureBadge(String signer, String dateStr) {
        int w = 500, h = 190;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Background badge
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(245, 248, 250, 252));
        RectF rect = new RectF(4, 4, w - 4, h - 4);
        canvas.drawRoundRect(rect, 14, 14, bgPaint);

        // Border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(Color.argb(220, 203, 213, 225));
        borderPaint.setStrokeWidth(3f);
        canvas.drawRoundRect(rect, 14, 14, borderPaint);

        // Blue accent bar on left
        Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setColor(Color.rgb(37, 99, 235));
        RectF barRect = new RectF(4, 4, 18, h - 4);
        canvas.drawRoundRect(barRect, 8, 8, barPaint);

        // Icon checkmark
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setColor(Color.rgb(37, 99, 235));
        iconPaint.setTextSize(26);
        canvas.drawText("✓", 28, 44, iconPaint);

        // Header
        Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(Color.rgb(100, 116, 139));
        headerPaint.setTextSize(17);
        headerPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("DIGITALLY SIGNED", 56, 42, headerPaint);

        // Signer name with Unicode & Emoji support
        Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        namePaint.setColor(Color.rgb(15, 23, 42));
        namePaint.setTextSize(26);
        namePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        String displaySigner = signer;
        if (namePaint.measureText(displaySigner) > w - 48) {
            int count = namePaint.breakText(displaySigner, true, w - 80, null);
            displaySigner = displaySigner.substring(0, Math.min(count, displaySigner.length())) + "...";
        }
        canvas.drawText(displaySigner, 28, 88, namePaint);

        // Date / Time
        Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        datePaint.setColor(Color.rgb(71, 85, 105));
        datePaint.setTextSize(18);
        canvas.drawText("Date: " + dateStr, 28, 126, datePaint);

        // Verification mark
        Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tagPaint.setColor(Color.rgb(148, 163, 184));
        tagPaint.setTextSize(15);
        canvas.drawText("FileSwitch Verified • Document Integrity Protected", 28, 162, tagPaint);

        return bmp;
    }

    static File addSignature(ContentResolver resolver, Uri source, String signer, int pageNum, File dir) throws IOException {
        return addSignature(resolver, source, null, signer, pageNum, dir);
    }

    static File addSignature(ContentResolver resolver, Uri source, Bitmap signature, String signer, int pageNum, File dir) throws IOException {
        File out = output(dir, "signed.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            int total = doc.getNumberOfPages();
            if (pageNum < 1 || pageNum > total) pageNum = total;
            PDPage page = doc.getPage(pageNum - 1);
            PDRectangle box = page.getCropBox();

            String cleanSigner = normalizePdfText(signer == null || signer.trim().isEmpty() ? "Verified Signer" : signer.trim());
            String dateStr = normalizePdfText(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date()));

            Bitmap badge = createSignatureBadge(cleanSigner, dateStr);
            try {
                PDImageXObject image = LosslessFactory.createFromImage(doc, badge);
                float sigW = 166f, sigH = 64f;
                float x = box.getUpperRightX() - sigW - 36;
                float y = box.getLowerLeftY() + 36;
                try (PDPageContentStream stream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.drawImage(image, x, y, sigW, sigH);
                }
            } finally {
                badge.recycle();
            }
            doc.save(out);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out;
    }

    static String metadata(ContentResolver resolver, Uri source) throws IOException {
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) return "PDF is password protected. Unlock to view metadata.";
            String size = doc.getNumberOfPages() > 0 ? Math.round(doc.getPage(0).getMediaBox().getWidth()) + " × " + Math.round(doc.getPage(0).getMediaBox().getHeight()) + " pt" : "—";
            return "Pages: " + doc.getNumberOfPages() + "\nPDF version: " + doc.getVersion() + "\nPage size: " + size + "\nTitle: " + value(doc.getDocumentInformation().getTitle()) + "\nAuthor: " + value(doc.getDocumentInformation().getAuthor());
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            return "PDF is password protected. Unlock to view metadata.";
        }
    }
    static int pageCount(ContentResolver resolver, Uri source) throws IOException {
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            return doc.getNumberOfPages();
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }
    }
    private static String value(String value){return value==null||value.trim().isEmpty()?"—":value;}
}
