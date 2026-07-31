package interview.homegrown.infrastructure.file;


import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件内容哈希服务
 * 使用 SHA-256 对文件字节计算唯一指纹，用于：
 * - 内容去重（同一份文件不重复存储/分析）
 * - 完整性校验
 */
@Service
public class FileHashService {

    //计算文件内容的SHA-256哈希（十六进制字符串）
    public String computesha256(byte[] bytes){
        try{
            //获取一个SHA-256算法的摘要器对象（无论数据输入多大都会生成固定32字节的哈希值）
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            //计算哈希值
            byte[] hash = digest.digest(bytes);

            //将字节数组转换为标准的小写十六进制字符串
            return HexFormat.of().formatHex(hash);
        }catch (NoSuchAlgorithmException e){
            throw new IllegalStateException("当前 JVM 不支持 SHA-256 算法",e);
        }
    }
}
