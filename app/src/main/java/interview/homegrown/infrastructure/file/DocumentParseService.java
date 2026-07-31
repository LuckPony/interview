package interview.homegrown.infrastructure.file;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 文档解析服务（基于 Apache Tika）
 * 能力：
 * 1. parseText()      —— 解析 PDF / DOCX / TXT 等为纯文本
 * 2. detectContentType() —— 基于字节内容检测 MIME 类型（不信任文件扩展名）
 */
@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    private final TextCleaningService textCleaningService;

    public DocumentParseService(TextCleaningService textCleaningService) {
        this.textCleaningService = textCleaningService;
    }

    /**
     * 解析文档为纯文本
     * @param bytes     文件字节
     * @param fileName  原始文件名（用于解析器识别格式）
     * @return 清洗后的纯文本
     */
    public String parseText(byte[] bytes, String fileName){
        //Tika经典的四步解析法
        //1.将传入的文件的二进制数据包装成一个字节输入流
        try(ByteArrayInputStream in = new ByteArrayInputStream(bytes)){
            //2.配置内容处理器，从Tika解析出的XHTML结构中只提取<body>标签内的纯文本,-1表示表示不限制文本长度
            BodyContentHandler handler = new BodyContentHandler(-1);

            //3.创建元数据对象，参考文件名解析判断使用什么解析器
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

            //4.解析上下文
            ParseContext context = new ParseContext();

            //创建自动解析器进行解析
            Parser parser = new AutoDetectParser();
            parser.parse(in, handler, metadata,context);//执行解析
            return textCleaningService.clean(handler.toString());

        }catch (IOException | TikaException | SAXException e){
            log.error("文档解析失败：fileName={}",fileName,e);
            throw new BusinessException(ErrorCode.FILE_PARSE_FAILED,fileName);
        }

    }
    //检测内容类型，基于文件字节内容判断真实 MIME 类型，防止伪造扩展名
    public String detectContectType(byte[] bytes){
        try(ByteArrayInputStream in = new ByteArrayInputStream(bytes)){
            return new Tika().detect(in);
        }catch(IOException e){
            log.warn("内容类型检测失败，返回 octet-stream", e);
            return "application/octet-stream";
        }
    }

}
