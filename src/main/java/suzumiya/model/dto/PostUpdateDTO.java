package suzumiya.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class PostUpdateDTO {

    private long postId;
    private String title;
    private String content;
    private List<Integer> tagIDs;
}