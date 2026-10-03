package com.fileswitch.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;

import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
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
    static File insertImage(ContentResolver resolver,Uri source,Bitmap bitmap,int pageNumber,File dir)throws IOException{
        File out=output(dir,"image-added.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if(pageNumber<1||pageNumber>doc.getNumberOfPages())throw new IOException("Choose a page between 1 and "+doc.getNumberOfPages());
            PDPage page=doc.getPage(pageNumber-1);
            PDRectangle box=page.getCropBox();
            PDImageXObject image=LosslessFactory.createFromImage(doc,bitmap);
            float scale=Math.min(box.getWidth()/bitmap.getWidth(),box.getHeight()/bitmap.getHeight())*.5f;
            float width=bitmap.getWidth()*scale,height=bitmap.getHeight()*scale;
            try(PDPageContentStream stream=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)){
                stream.drawImage(image,box.getLowerLeftX()+(box.getWidth()-width)/2,box.getLowerLeftY()+(box.getHeight()-height)/2,width,height);
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
    static File crop(ContentResolver resolver,Uri source,float margin,File dir) throws IOException {
        File out=output(dir,"cropped.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            for(PDPage page:doc.getPages()){
                PDRectangle box=page.getCropBox();
                float width=box.getWidth()-margin*2,height=box.getHeight()-margin*2;
                if(width<20||height<20)throw new IOException("Crop margin is too large");
                page.setCropBox(new PDRectangle(box.getLowerLeftX()+margin,box.getLowerLeftY()+margin,width,height));
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
    static File annotate(ContentResolver resolver,Uri source,String text,File dir) throws IOException {
        File out=output(dir,"annotated.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){
            if(doc.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            int number=0;
            for(PDPage page:doc.getPages()){
                number++;
                try(PDPageContentStream stream=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)){
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA,10);
                    stream.newLineAtOffset(24,18);
                    stream.showText(text.replaceAll("[^\\x20-\\x7E]","?")+"  "+number);
                    stream.endText();
                }
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
    static File compress(ContentResolver resolver,Uri source,int dpi,float quality,File dir) throws IOException {
        File out=output(dir,"compressed.pdf");
        try(InputStream in=resolver.openInputStream(source);PDDocument original=PDDocument.load(in);PDDocument compressed=new PDDocument()){
            if(original.isEncrypted())throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            if(original.getNumberOfPages()>100)throw new IOException("This PDF has more than 100 pages");
            PDFRenderer renderer=new PDFRenderer(original);
            for(int i=0;i<original.getNumberOfPages();i++){
                PDPage old=original.getPage(i);
                PDRectangle box=old.getMediaBox();
                double pixels=box.getWidth()/72.0*dpi*box.getHeight()/72.0*dpi;
                if(pixels>12_000_000)throw new IOException("A page is too large to compress safely on this phone");
                Bitmap bitmap=renderer.renderImageWithDPI(i,dpi,ImageType.RGB);
                try{
                    PDPage page=new PDPage(box);
                    compressed.addPage(page);
                    PDImageXObject image=JPEGFactory.createFromImage(compressed,bitmap,quality);
                    try(PDPageContentStream content=new PDPageContentStream(compressed,page)){
                        content.drawImage(image,0,0,box.getWidth(),box.getHeight());
                    }
                }finally{
                    bitmap.recycle();
                }
            }
            compressed.save(out);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){
            out.delete();
            throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
        }catch(IOException e){
            out.delete();
            throw e;
        }
        return out;
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

    static File stamp(ContentResolver resolver, Uri source, String text, int position, File dir) throws IOException {
        File out = output(dir, "stamped.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getCropBox();
                try (PDPageContentStream stream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.setFont(PDType1Font.HELVETICA_BOLD, position == 0 ? 32 : 14);
                    stream.setNonStrokingColor(200, 30, 30);
                    if (position == 0) {
                        float cx = box.getLowerLeftX() + box.getWidth() / 4f;
                        float cy = box.getLowerLeftY() + box.getHeight() / 2f;
                        stream.beginText();
                        stream.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(35), cx, cy));
                        stream.newLineAtOffset(cx, cy);
                        stream.showText(text);
                        stream.endText();
                    } else if (position == 1) {
                        stream.beginText();
                        stream.newLineAtOffset(box.getLowerLeftX() + 36, box.getUpperRightY() - 36);
                        stream.showText(text);
                        stream.endText();
                    } else {
                        stream.beginText();
                        stream.newLineAtOffset(box.getLowerLeftX() + 36, box.getLowerLeftY() + 24);
                        stream.showText(text);
                        stream.endText();
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

    static File addSignature(ContentResolver resolver, Uri source, Bitmap signature, String signer, int pageNum, File dir) throws IOException {
        File out = output(dir, "signed.pdf");
        try (InputStream in = resolver.openInputStream(source); PDDocument doc = PDDocument.load(in)) {
            if (doc.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            int total = doc.getNumberOfPages();
            if (pageNum < 1 || pageNum > total) pageNum = total;
            PDPage page = doc.getPage(pageNum - 1);
            PDRectangle box = page.getCropBox();
            PDImageXObject image = LosslessFactory.createFromImage(doc, signature);
            float sigW = 140, sigH = 50;
            float x = box.getUpperRightX() - sigW - 36;
            float y = box.getLowerLeftY() + 36;
            try (PDPageContentStream stream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.drawImage(image, x, y + 20, sigW, sigH);
                stream.setFont(PDType1Font.HELVETICA_BOLD, 9);
                stream.setNonStrokingColor(30, 30, 30);
                stream.beginText();
                stream.newLineAtOffset(x, y + 8);
                stream.showText("Digitally Signed by: " + (signer == null || signer.isEmpty() ? "User" : signer));
                stream.endText();
                stream.setFont(PDType1Font.HELVETICA, 8);
                stream.beginText();
                stream.newLineAtOffset(x, y - 2);
                stream.showText("Date: " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()));
                stream.endText();
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

    static String metadata(ContentResolver resolver,Uri source) throws IOException {try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){String size=doc.getNumberOfPages()>0?Math.round(doc.getPage(0).getMediaBox().getWidth())+" × "+Math.round(doc.getPage(0).getMediaBox().getHeight())+" pt":"—";return "Pages: "+doc.getNumberOfPages()+"\nPDF version: "+doc.getVersion()+"\nPage size: "+size+"\nTitle: "+value(doc.getDocumentInformation().getTitle())+"\nAuthor: "+value(doc.getDocumentInformation().getAuthor());}}
    static int pageCount(ContentResolver resolver,Uri source) throws IOException {try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){return doc.getNumberOfPages();}}
    private static String value(String value){return value==null||value.trim().isEmpty()?"—":value;}
}
