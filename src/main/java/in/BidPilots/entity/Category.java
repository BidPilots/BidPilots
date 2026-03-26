package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_name", nullable = false, length = 500)
    private String categoryName;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "is_deactive")
    private Boolean isDeactive = false;

    @PrePersist
    protected void onCreate() {
        if (isActive == null) isActive = true;
        if (isDeactive == null) isDeactive = false;
    }
    

}