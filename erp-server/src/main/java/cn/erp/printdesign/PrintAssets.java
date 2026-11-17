package cn.erp.printdesign;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

import static cn.erp.printdesign.PrintFields.invalid;

final class PrintAssets {
    private static final Set<String> PREFIXES=Set.of("data:image/png;base64,","data:image/jpeg;base64,");
    private static final int MAX_ENCODED_SIZE=350_000;
    private static final int MAX_DIMENSION=1800;

    private PrintAssets() {}

    static BufferedImage image(String data) {
        if(data==null || data.length()>MAX_ENCODED_SIZE) throw invalid("每张模板图片须小于 256 KB");
        String prefix=PREFIXES.stream().filter(data::startsWith).findFirst().orElseThrow(()->invalid("请上传 PNG 或 JPEG 图片"));
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data.substring(prefix.length()))))) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext()) throw invalid("图片内容无法识别");
            var reader=readers.next();
            try {
                if(!prefix.equals("data:image/"+reader.getFormatName().toLowerCase(java.util.Locale.ROOT)+";base64,")) throw invalid("图片类型与内容不符");
                reader.setInput(stream,true,true);
                if(reader.getWidth(0)>MAX_DIMENSION || reader.getHeight(0)>MAX_DIMENSION) throw invalid("图片边长不得超过 1800 像素");
                return reader.read(0);
            } finally {reader.dispose();}
        } catch(IOException | IllegalArgumentException error) {throw invalid("图片内容无效");}
    }

    static String barcode(String value,boolean qr) {
        if(value.isBlank()) return null;
        if(value.length()>500) throw invalid("二维码内容超过允许长度");
        try {
            boolean encodeQr=qr || value.length()>80 || value.codePoints().anyMatch(c->c<32 || c>126);
            BitMatrix bits=encodeQr?new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,1,1,Map.of(EncodeHintType.CHARACTER_SET,"UTF-8"))
                    :new Code128Writer().encode(value,BarcodeFormat.CODE_128,1,1);
            int scale=encodeQr?3:2, height=encodeQr?bits.getHeight()*scale:54;
            var image=new BufferedImage(bits.getWidth()*scale,height,BufferedImage.TYPE_BYTE_BINARY);
            int white=Color.WHITE.getRGB(),black=Color.BLACK.getRGB();
            for(int y=0;y<height;y++) for(int x=0;x<image.getWidth();x++) image.setRGB(x,y,bits.get(x/scale,encodeQr?y/scale:0)?black:white);
            var output=new ByteArrayOutputStream();
            ImageIO.write(image,"png",output);
            return "data:image/png;base64,"+Base64.getEncoder().encodeToString(output.toByteArray());
        } catch(com.google.zxing.WriterException | IllegalArgumentException | IOException error) {throw invalid("业务数据无法编码为条码");}
    }
}
