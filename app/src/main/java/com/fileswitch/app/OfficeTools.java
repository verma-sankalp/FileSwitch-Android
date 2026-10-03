package com.fileswitch.app;

import android.content.ContentResolver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Html;
import android.os.Build;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class OfficeTools {
    static final class Result {final File file;final String name,mime;Result(File f,String n,String m){file=f;name=n;mime=m;}}
    private OfficeTools(){}
    static Result convert(ContentResolver resolver,Uri uri,String name,String source,String target,File dir)throws IOException {
        String text;
        if(source.equals("docx"))text=docxText(readZip(resolver,uri).get("word/document.xml"));
        else if(source.equals("xlsx"))text=xlsxCsv(readZip(resolver,uri));
        else if(source.equals("xls"))text=xlsCsv(resolver,uri);
        else if(source.equals("pptx"))text=pptxText(readZip(resolver,uri));
        else if(source.equals("odt"))text=odtText(readZip(resolver,uri).get("content.xml"));
        else text=readText(resolver,uri);
        if(source.equals("html"))text=htmlText(text);if(source.equals("rtf"))text=rtfText(text);
        if(source.equals("csv"))text=csv(text);
        String content=text;
        if(target.equals("pdf"))return new Result(writePdf(content,dir,name),base(name)+".pdf","application/pdf");
        if(target.equals("docx"))return new Result(writeDocx(content,dir,name),base(name)+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if(target.equals("xlsx"))return new Result(writeXlsx(source.equals("csv")?text:toCsv(text),dir,name),base(name)+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        if(target.equals("html")){content=source.equals("md")?markdownHtml(content):"<!doctype html><meta charset=\"utf-8\"><pre>"+escape(content)+"</pre>";return write(dir,name,"html","text/html",content);}
        if(target.equals("txt"))return write(dir,name,"txt","text/plain",content);
        if(target.equals("csv"))return write(dir,name,"csv","text/csv",content);
        throw new IOException("That document conversion is not available");
    }
    private static Result write(File dir,String name,String ext,String mime,String content)throws IOException{File out=output(dir,base(name)+"."+ext);try(FileOutputStream stream=new FileOutputStream(out)){stream.write(content.getBytes(StandardCharsets.UTF_8));}return new Result(out,base(name)+"."+ext,mime);}
    private static String readText(ContentResolver resolver,Uri uri)throws IOException{try(InputStream in=resolver.openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))>0){if(out.size()+n>25*1024*1024)throw new IOException("Text file is too large");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static LinkedHashMap<String,String> readZip(ContentResolver resolver,Uri uri)throws IOException{LinkedHashMap<String,String> entries=new LinkedHashMap<>();int total=0;try(InputStream in=resolver.openInputStream(uri);ZipInputStream zip=new ZipInputStream(in)){ZipEntry e;byte[] b=new byte[16384];while((e=zip.getNextEntry())!=null){if(e.isDirectory())continue;if(e.getName().contains(".."))throw new IOException("Unsafe document archive");if(entries.size()>1000)throw new IOException("Office document has too many parts");ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=zip.read(b))>0){total+=n;if(out.size()+n>25*1024*1024||total>50*1024*1024)throw new IOException("Office document is too large");out.write(b,0,n);}entries.put(e.getName(),new String(out.toByteArray(),StandardCharsets.UTF_8));}}return entries;}
    private static String docxText(String xml)throws IOException{if(xml==null)throw new IOException("Word document is missing its main content");String plain=xml.replaceAll("(?i)</w:p>","\n").replaceAll("<[^>]+>","");return Html.fromHtml(plain,Html.FROM_HTML_MODE_LEGACY).toString();}
    private static String pptxText(LinkedHashMap<String,String> zip)throws IOException{ArrayList<String> slides=new ArrayList<>();for(String key:zip.keySet())if(key.matches("ppt/slides/slide[0-9]+\\.xml"))slides.add(key);slides.sort((a,b)->Integer.compare(slideNumber(a),slideNumber(b)));if(slides.isEmpty())throw new IOException("Presentation has no slides");StringBuilder text=new StringBuilder();for(String path:slides){String xml=zip.get(path);Matcher paragraphs=Pattern.compile("<a:p\\b[^>]*>(.*?)</a:p>",Pattern.DOTALL).matcher(xml);while(paragraphs.find()){Matcher run=Pattern.compile("<a:t\\b[^>]*>(.*?)</a:t>",Pattern.DOTALL).matcher(paragraphs.group(1));StringBuilder line=new StringBuilder();while(run.find())line.append(xmlUnescape(run.group(1)));if(line.length()>0)text.append(line).append('\n');}text.append('\n');}return text.toString();}
    private static int slideNumber(String path){Matcher number=Pattern.compile("slide([0-9]+)\\.xml").matcher(path);return number.find()?Integer.parseInt(number.group(1)):0;}
    private static String odtText(String xml)throws IOException{if(xml==null)throw new IOException("OpenDocument text is missing its content.xml");String plain=xml.replaceAll("(?i)</text:(p|h)>","\n").replaceAll("<text:tab[^>]*/>","\t").replaceAll("<text:line-break[^>]*/>","\n").replaceAll("<text:s\\b[^>]*/>"," ").replaceAll("<[^>]+>","");return xmlUnescape(plain);}
    private static String xlsxCsv(LinkedHashMap<String,String> zip)throws IOException{String sharedXml=zip.getOrDefault("xl/sharedStrings.xml","");ArrayList<String> shared=new ArrayList<>();Matcher si=Pattern.compile("<si\\b[^>]*>(.*?)</si>",Pattern.DOTALL).matcher(sharedXml);while(si.find()){Matcher t=Pattern.compile("<t\\b[^>]*>(.*?)</t>",Pattern.DOTALL).matcher(si.group(1));StringBuilder value=new StringBuilder();while(t.find())value.append(xmlUnescape(t.group(1)));shared.add(value.toString());}String sheet=null;for(String key:zip.keySet())if(key.matches("xl/worksheets/sheet[0-9]+\\.xml")){sheet=zip.get(key);break;}if(sheet==null)throw new IOException("Workbook has no worksheet");StringBuilder out=new StringBuilder();Matcher rows=Pattern.compile("<row\\b[^>]*>(.*?)</row>",Pattern.DOTALL).matcher(sheet);while(rows.find()){ArrayList<String> cells=new ArrayList<>();Matcher cell=Pattern.compile("<c\\b([^>]*)>(.*?)</c>",Pattern.DOTALL).matcher(rows.group(1));while(cell.find()){String ref=attr(cell.group(1),"r");int col=column(ref);while(cells.size()<=col)cells.add("");String value=tag(cell.group(2),"v");if("s".equals(attr(cell.group(1),"t"))&&!value.isEmpty()){int index=Integer.parseInt(value);if(index>=0&&index<shared.size())value=shared.get(index);}else if("inlineStr".equals(attr(cell.group(1),"t")))value=tag(cell.group(2),"t");cells.set(col,xmlUnescape(value));}out.append(toCsvRow(cells)).append('\n');}return out.toString();}
    private static String xlsCsv(ContentResolver resolver,Uri uri)throws IOException{try(InputStream in=resolver.openInputStream(uri);HSSFWorkbook workbook=new HSSFWorkbook(in)){StringBuilder csv=new StringBuilder();for(int s=0;s<workbook.getNumberOfSheets();s++){if(s>0)csv.append('\n');for(Row row:workbook.getSheetAt(s)){ArrayList<String> values=new ArrayList<>();for(Cell cell:row)values.add(cell.toString());csv.append(toCsvRow(values)).append('\n');}}return csv.toString();}catch(RuntimeException e){throw new IOException("This Excel file could not be read",e);}}
    private static int column(String ref){int value=0;for(int i=0;i<ref.length()&&Character.isLetter(ref.charAt(i));i++)value=value*26+Character.toUpperCase(ref.charAt(i))-'A'+1;return Math.max(0,value-1);}
    private static String attr(String attrs,String name){Matcher m=Pattern.compile("(?:^|\\s)"+name+"=\"([^\"]*)\"").matcher(attrs);return m.find()?m.group(1):"";}
    private static String tag(String xml,String name){Matcher m=Pattern.compile("<"+name+"\\b[^>]*>(.*?)</"+name+">",Pattern.DOTALL).matcher(xml);return m.find()?m.group(1):"";}
    private static String csv(String input){return input;}
    private static String toCsv(String input){return input;}
    private static String toCsvRow(List<String> values){StringBuilder row=new StringBuilder();for(int i=0;i<values.size();i++){if(i>0)row.append(',');String v=values.get(i);if(v.contains(",")||v.contains("\"")||v.contains("\n"))row.append('"').append(v.replace("\"","\"\"")).append('"');else row.append(v);}return row.toString();}
    private static String htmlText(String html){if(Build.VERSION.SDK_INT>=24)return Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString();return Html.fromHtml(html).toString();}
    private static String markdownHtml(String markdown){StringBuilder html=new StringBuilder("<!doctype html><meta charset=\"utf-8\"><article>");for(String line:markdown.split("\\R")){String escaped=escape(line.trim());int heading=0;while(heading<6&&escaped.startsWith("#")){heading++;escaped=escaped.substring(1);}if(heading>0&&escaped.startsWith(" "))html.append("<h").append(heading).append(">").append(escaped.trim()).append("</h").append(heading).append(">");else if(escaped.startsWith("- ")||escaped.startsWith("* "))html.append("<li>").append(escaped.substring(2)).append("</li>");else if(!escaped.isEmpty())html.append("<p>").append(escaped).append("</p>");}return html.append("</article>").toString();}
    private static String rtfText(String rtf){return rtf.replaceAll("\\\\'[0-9a-fA-F]{2}"," ").replaceAll("\\\\[a-zA-Z]+-?\\d* ?"," ").replaceAll("[{}]","");}
    static File writeDocx(String text,File dir,String name)throws IOException{File file=output(dir,base(name)+".docx");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){entry(zip,"[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");entry(zip,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");StringBuilder body=new StringBuilder();for(String line:text.split("\\R",-1))body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escape(line)).append("</w:t></w:r></w:p>");entry(zip,"word/document.xml","<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr/></w:body></w:document>");}return file;}
    private static File writeXlsx(String csv,File dir,String name)throws IOException{File file=output(dir,base(name)+".xlsx");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){entry(zip,"[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");entry(zip,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");entry(zip,"xl/workbook.xml","<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");entry(zip,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");StringBuilder sheet=new StringBuilder("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");String[] lines=csv.split("\\R");for(int r=0;r<lines.length;r++){List<String> cells=parseCsvLine(lines[r]);sheet.append("<row r=\"").append(r+1).append("\">");for(int c=0;c<cells.size();c++)sheet.append("<c r=\"").append(cellRef(c,r+1)).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(cells.get(c))).append("</t></is></c>");sheet.append("</row>");}sheet.append("</sheetData></worksheet>");entry(zip,"xl/worksheets/sheet1.xml",sheet.toString());}return file;}
    private static List<String> parseCsvLine(String line){ArrayList<String> values=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(ch=='\"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='\"'){cell.append('\"');i++;}else quoted=!quoted;}else if(ch==','&&!quoted){values.add(cell.toString());cell.setLength(0);}else cell.append(ch);}values.add(cell.toString());return values;}
    private static String cellRef(int col,int row){StringBuilder s=new StringBuilder();for(col++;col>0;col=(col-1)/26)s.insert(0,(char)('A'+(col-1)%26));return s+Integer.toString(row);}
    private static void entry(ZipOutputStream zip,String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static File writePdf(String text,File dir,String name)throws IOException{File file=output(dir,base(name)+".pdf");PdfDocument pdf=new PdfDocument();Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(12);int width=595,height=842,left=42,top=48,lineHeight=18,max=(width-left*2)/7;String[] lines=text.replace("\r","").split("\\n");PdfDocument.Page page=null;Canvas canvas=null;int y=0,pageNumber=0;try{for(String paragraph:lines){String remaining=paragraph.isEmpty()?" ":paragraph;while(!remaining.isEmpty()){if(page==null||y>height-top){if(page!=null)pdf.finishPage(page);page=pdf.startPage(new PdfDocument.PageInfo.Builder(width,height,++pageNumber).create());canvas=page.getCanvas();y=top;}int take=Math.min(max,remaining.length());while(take>1&&take<remaining.length()&&remaining.charAt(take)!=' '&&remaining.charAt(take-1)!=' ')take--;canvas.drawText(remaining.substring(0,take),left,y,paint);remaining=remaining.substring(take).trim();y+=lineHeight;}}if(page!=null)pdf.finishPage(page);try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}}catch(RuntimeException e){file.delete();throw new IOException("Could not create PDF",e);}finally{pdf.close();}return file;}
    private static File output(File dir,String name)throws IOException{if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not prepare output");return new File(dir,UUID.randomUUID()+"-"+name);}
    private static String base(String name){String value=name.replaceFirst("\\.[^.]*$","");return value.isEmpty()?"converted-file":value;}
    static Result repairDocument(ContentResolver resolver, Uri uri, String name, String source, File dir) throws IOException {
        String text = null;
        try {
            if (source.equals("docx")) text = docxText(readZip(resolver, uri).get("word/document.xml"));
            else if (source.equals("pptx")) text = pptxText(readZip(resolver, uri));
            else if (source.equals("odt")) text = odtText(readZip(resolver, uri).get("content.xml"));
            else if (source.equals("xlsx")) text = xlsxCsv(readZip(resolver, uri));
            else if (source.equals("xls")) text = xlsCsv(resolver, uri);
            else text = readText(resolver, uri);
        } catch (Exception e) {
            text = salvageRawText(resolver, uri);
        }
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("No salvageable text found in corrupted document");
        }
        File out = writeDocx(text, dir, "repaired-" + base(name));
        return new Result(out, "repaired-" + base(name) + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private static String salvageRawText(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream in = resolver.openInputStream(uri); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Document could not be opened");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (baos.size() + n > 25 * 1024 * 1024) break;
                baos.write(buf, 0, n);
            }
            String raw = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            StringBuilder salvaged = new StringBuilder();
            Matcher m = Pattern.compile("<(?:w:t|t|text:p)[^>]*>(.*?)</(?:w:t|t|text:p)>", Pattern.DOTALL).matcher(raw);
            while (m.find()) {
                String str = xmlUnescape(m.group(1).replaceAll("<[^>]+>", ""));
                if (!str.trim().isEmpty()) {
                    salvaged.append(str).append(" ");
                }
            }
            if (salvaged.length() > 0) return salvaged.toString();
            Matcher words = Pattern.compile("[A-Za-z0-9 ,.?!'\"-]{4,}").matcher(raw);
            while (words.find()) {
                salvaged.append(words.group()).append("\n");
            }
            return salvaged.toString();
        }
    }

    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String xmlUnescape(String s){return s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace("&amp;","&");}
}
