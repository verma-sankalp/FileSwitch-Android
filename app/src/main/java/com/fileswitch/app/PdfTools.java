package com.fileswitch.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

final class PdfTools {
    static final class Result {
        final File file;
        final String name, mime;
        Result(File file,String name,String mime){this.file=file;this.name=name;this.mime=mime;}
    }

    private PdfTools() {}

    static void init(android.content.Context context){PDFBoxResourceLoader.init(context.getApplicationContext());}

    static ArrayList<Result> convert(ContentResolver resolver,Uri source,String sourceName,String target,int dpi,File outputDir) throws IOException {
        ArrayList<Result> outputs=new ArrayList<>();
        try(InputStream stream=resolver.openInputStream(source);PDDocument document=PDDocument.load(stream)) {
            if(document.isEncrypted()) throw new IOException("This PDF is password protected. Use PDF Unlock tool first.");
            String base=sourceName.replaceFirst("\\.[^.]*$","");if(base.isEmpty())base="converted-file";
            if(target.equals("txt")||target.equals("html")||target.equals("md")||target.equals("docx")) {
                String content=new PDFTextStripper().getText(document);
                if(target.equals("html"))content="<!doctype html><meta charset=\"utf-8\"><pre>"+escape(content)+"</pre>";
                if(target.equals("md"))content=content.replace("\r\n","\n");
                if(target.equals("docx")){File out=OfficeTools.writeDocx(content,outputDir,base+".docx");outputs.add(new Result(out,base+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"));return outputs;}
                String ext=target.equals("md")?"md":target;String mime=target.equals("html")?"text/html":"text/plain";
                File out=new File(outputDir,UUID.randomUUID()+"-"+base+"."+ext);try(FileOutputStream os=new FileOutputStream(out)){os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                outputs.add(new Result(out,base+"."+ext,mime));return outputs;
            }
            if(!target.equals("png")&&!target.equals("jpg")&&!target.equals("webp"))throw new IOException("That PDF conversion is not available");
            if(document.getNumberOfPages()>100)throw new IOException("This PDF has more than 100 pages");
            PDFRenderer renderer=new PDFRenderer(document);
            for(int i=0;i<document.getNumberOfPages();i++) {
                double pixels=document.getPage(i).getMediaBox().getWidth()/72.0*dpi*document.getPage(i).getMediaBox().getHeight()/72.0*dpi;if(pixels>20_000_000)throw new IOException("A page is too large at this DPI; choose a lower setting");Bitmap bitmap=renderer.renderImageWithDPI(i,dpi,ImageType.RGB);String ext=target.equals("jpg")?"jpg":target;
                File out=new File(outputDir,UUID.randomUUID()+"-"+base+"-page-"+(i+1)+"."+ext);
                Bitmap.CompressFormat format=target.equals("jpg")?Bitmap.CompressFormat.JPEG:target.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.WEBP;
                try(FileOutputStream os=new FileOutputStream(out)){if(!bitmap.compress(format,target.equals("png")?100:90,os))throw new IOException("Image export failed");}finally{bitmap.recycle();}ImageTools.setDpi(out,dpi);
                String mime="image/"+(target.equals("jpg")?"jpeg":target);outputs.add(new Result(out,base+"-page-"+(i+1)+"."+ext,mime));
            }
            return outputs;
        } catch(IOException e){throw e;} catch(Exception e){throw new IOException("This PDF could not be read",e);}
    }

    static int pageCount(ContentResolver resolver,Uri source) throws IOException {try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){return doc.getNumberOfPages();}}
    static Bitmap thumbnail(ContentResolver resolver,Uri source)throws IOException{try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){if(doc.getNumberOfPages()==0)throw new IOException("This PDF has no pages");com.tom_roush.pdfbox.pdmodel.common.PDRectangle box=doc.getPage(0).getMediaBox();double pixels=box.getWidth()/72.0*48*box.getHeight()/72.0*48;if(pixels>4_000_000)throw new IOException("PDF page is too large to preview");return new PDFRenderer(doc).renderImageWithDPI(0,48,ImageType.RGB);}}
    static String metadata(ContentResolver resolver,Uri source) throws IOException {try(InputStream in=resolver.openInputStream(source);PDDocument doc=PDDocument.load(in)){com.tom_roush.pdfbox.pdmodel.PDDocumentInformation info=doc.getDocumentInformation();return String.format(Locale.getDefault(),"Pages: %d\nPDF version: %.1f\nTitle: %s\nAuthor: %s",doc.getNumberOfPages(),doc.getVersion(),safe(info.getTitle()),safe(info.getAuthor()));}}
    private static String safe(String value){return value==null||value.trim().isEmpty()?"—":value;}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}
