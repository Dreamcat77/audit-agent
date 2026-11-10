package com.fuquan.audit.integration.material;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.common.exception.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.springframework.stereotype.Component;

@Component
public class OriginalPageRenderer implements MaterialPageRenderer {
    private final Semaphore slots=new Semaphore(2,true);
    @Override public String legacyWord(InputStream input,long size,String sha)throws IOException {
        if(size<1||size>60L*1024*1024||!VisualIndex.hash(sha))throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        if(!slots.tryAcquire())throw new BusinessException(BusinessErrorCode.CONFLICT);
        try {
            byte[] bytes=input.readNBytes((int)size+1);
            if(bytes.length!=size||!MaterialVisualEvidence.digest(bytes).equals(sha))throw new BusinessException(BusinessErrorCode.CONFLICT);
            try(var word=new org.apache.poi.hwpf.HWPFDocument(new ByteArrayInputStream(bytes))){
                var dom=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                var converter=new org.apache.poi.hwpf.converter.WordToHtmlConverter(dom);
                converter.setPicturesManager((content,type,suggested,width,height)->{
                    String mime=type.getMime();
                    return SetOfPreviewImages.allowed(mime)?"data:"+mime+";base64,"+java.util.Base64.getEncoder().encodeToString(content):"";
                });
                converter.processDocument(word);
                var factory=javax.xml.transform.TransformerFactory.newInstance();
                factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
                var transformer=factory.newTransformer();transformer.setOutputProperty(javax.xml.transform.OutputKeys.METHOD,"html");
                var output=new StringWriter();transformer.transform(new javax.xml.transform.dom.DOMSource(converter.getDocument()),new javax.xml.transform.stream.StreamResult(output));
                if(output.getBuffer().length()>12*1024*1024)throw new BusinessException(BusinessErrorCode.PAYLOAD_TOO_LARGE);
                return output.toString();
            }
        }catch(javax.xml.parsers.ParserConfigurationException|javax.xml.transform.TransformerException failure){throw new IOException("LEGACY_WORD_PREVIEW_FAILED",failure);}
        finally{slots.release();}
    }
    private static final class SetOfPreviewImages {
        static boolean allowed(String mime){return java.util.Set.of("image/png","image/jpeg","image/gif","image/bmp").contains(mime);}
    }
    public Image render(InputStream input,long size,String sha,int number)throws IOException {
        if(number<1||number>1024||size<1||size>1024L*1024*1024||!VisualIndex.hash(sha))throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        if(!slots.tryAcquire())throw new BusinessException(BusinessErrorCode.CONFLICT,"原页正在读取，请稍后重试");
        Path path=null;
        try {
            path=Files.createTempFile("audit-visual-page-",".bin");
            var digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;
            try(var out=Files.newOutputStream(path)){
                byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1){count+=n;if(count>size)throw new IOException("SOURCE_CHANGED");digest.update(bytes,0,n);out.write(bytes,0,n);}
            }
            if(count!=size||!java.util.HexFormat.of().formatHex(digest.digest()).equals(sha))throw new BusinessException(BusinessErrorCode.CONFLICT,"材料原文件核对未通过");
            byte[] header;try(var in=Files.newInputStream(path)){header=in.readNBytes(12);}
            BufferedImage image;int pages;
            if(header.length>=5&&new String(header,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")){
                try(var doc=PDDocument.load(path.toFile(),MemoryUsageSetting.setupTempFileOnly())){
                    pages=doc.getNumberOfPages();if(doc.isEncrypted()||pages>1024||number>pages)throw new BusinessException(BusinessErrorCode.NOT_FOUND);
                    var page=doc.getPage(number-1);var box=page.getCropBox();
                    double area=(double)box.getWidth()*box.getHeight();if(!Double.isFinite(area)||area<=0)throw new IOException("PAGE_DIMENSIONS_INVALID");
                    float scale=(float)Math.min(2,Math.sqrt(4_000_000/area));
                    image=new PDFRenderer(doc).renderImage(number-1,scale,ImageType.RGB);
                }
            }else{
                try(var stream=ImageIO.createImageInputStream(path.toFile())){
                    var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"该格式暂不支持原页预览");
                    var reader=readers.next();try{
                        reader.setInput(stream);pages=reader.getNumImages(true);
                        if(number>pages||pages>1024)throw new BusinessException(BusinessErrorCode.NOT_FOUND);
                        int w=reader.getWidth(number-1),h=reader.getHeight(number-1);if((long)w*h>40_000_000)throw new IOException("IMAGE_PIXEL_LIMIT");
                        var param=reader.getDefaultReadParam();int sample=Math.max(1,(int)Math.ceil(Math.sqrt((double)w*h/4_000_000)));param.setSourceSubsampling(sample,sample,0,0);
                        image=reader.read(number-1,param);
                    }finally{reader.dispose();}
                }
                if(pages==1)image=orient(image,sourceOrientation(path));
            }
            try(var out=new ByteArrayOutputStream()){
                try{if(!ImageIO.write(image,"png",out))throw new IOException("IMAGE_ENCODING_FAILED");return new Image(out.toByteArray(),pages);}finally{image.flush();}
            }
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        finally{try{if(path!=null)Files.deleteIfExists(path);}finally{slots.release();}}
    }
    static BufferedImage orient(BufferedImage source,int orientation){
        int w=source.getWidth(),h=source.getHeight();if(orientation<=1||orientation>8)return source;
        boolean swap=orientation>=5;var target=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_RGB);
        AffineTransform t=switch(orientation){
            case 2->new AffineTransform(-1,0,0,1,w,0);case 3->new AffineTransform(-1,0,0,-1,w,h);
            case 4->new AffineTransform(1,0,0,-1,0,h);case 5->new AffineTransform(0,1,1,0,0,0);
            case 6->new AffineTransform(0,1,-1,0,h,0);case 7->new AffineTransform(0,-1,-1,0,h,w);
            case 8->new AffineTransform(0,-1,1,0,0,w);default->new AffineTransform();};
        var graphics=target.createGraphics();try{graphics.drawImage(source,t,null);}finally{graphics.dispose();source.flush();}return target;
    }
    static int sourceOrientation(Path file)throws IOException {
        try(var in=new DataInputStream(Files.newInputStream(file))){
            byte[] signature=in.readNBytes(8);
            if(java.util.Arrays.equals(signature,new byte[]{(byte)137,80,78,71,13,10,26,10})){
                // PNG eXIf carries the same TIFF header as JPEG EXIF, without "Exif\0\0".
                for(int chunks=0;chunks<1000;chunks++){
                    int length=in.readInt();byte[] type=in.readNBytes(4);if(length<0||type.length!=4)return 1;
                    String name=new String(type,java.nio.charset.StandardCharsets.US_ASCII);
                    if(name.equals("eXIf"))return length<=256*1024?tiffOrientation(in.readNBytes(length)):1;
                    if(name.equals("IEND"))return 1;in.skipNBytes((long)length+4);
                }
                return 1;
            }
        }catch(EOFException ignored){return 1;}
        try(var in=new DataInputStream(Files.newInputStream(file))){
            if(in.readUnsignedShort()!=0xffd8)return 1;
            for(int total=2;total<256*1024;){
                if(in.readUnsignedByte()!=0xff)return 1;int marker;do{marker=in.readUnsignedByte();}while(marker==0xff);
                if(marker==0xda||marker==0xd9)return 1;if(marker==0x01||marker>=0xd0&&marker<=0xd7)continue;
                int length=in.readUnsignedShort()-2;if(length<0)return 1;byte[] bytes=in.readNBytes(length);if(bytes.length!=length)return 1;total+=length+4;
                if(marker==0xe1&&length>=14&&bytes[0]=='E'&&bytes[1]=='x'&&bytes[2]=='i'&&bytes[3]=='f'&&bytes[4]==0&&bytes[5]==0){
                    return tiffOrientation(java.util.Arrays.copyOfRange(bytes,6,bytes.length));
                }
            }
        }catch(EOFException ignored){}return 1;
    }
    private static int tiffOrientation(byte[] bytes){
        if(bytes.length<8)return 1;var b=ByteBuffer.wrap(bytes);
        if(bytes[0]=='I'&&bytes[1]=='I')b.order(ByteOrder.LITTLE_ENDIAN);
        else if(bytes[0]=='M'&&bytes[1]=='M')b.order(ByteOrder.BIG_ENDIAN);else return 1;
        if(Short.toUnsignedInt(b.getShort(2))!=42)return 1;int offset=b.getInt(4);if(offset<8||offset>b.limit()-2)return 1;
        int count=Short.toUnsignedInt(b.getShort(offset));for(int i=0;i<count&&offset+2L+12L*i+12<=b.limit();i++){
            int p=offset+2+12*i;if(Short.toUnsignedInt(b.getShort(p))==274&&b.getShort(p+2)==3&&b.getInt(p+4)==1){int value=Short.toUnsignedInt(b.getShort(p+8));return value>=1&&value<=8?value:1;}
        }return 1;
    }
}
