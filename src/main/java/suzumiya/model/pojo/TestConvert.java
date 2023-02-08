package suzumiya.model.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class TestConvert {

    private String str;

    private Integer num1;
    private Double num2;
    private BigDecimal num3;
    private BigInteger num4;

//    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai") // 声明 序列化(与反序列化)的时间格式
//    private Date date1;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai") // 声明 序列化(与反序列化)的时间格式
    private LocalDateTime date2;
}
