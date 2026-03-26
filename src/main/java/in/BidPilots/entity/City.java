package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@Entity
@Table(name = "cities")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class City {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "city_name", nullable = false, length = 100)
	private String cityName;

	@Column(name = "is_active")
	private Boolean isActive = true;

	@Column(name = "is_deactive")
	private Boolean isDeactive = false;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "state_id", nullable = false)
	@JsonIgnoreProperties({ "cities", "bids" })
	private State state;

	@OneToMany(mappedBy = "consigneeCity", fetch = FetchType.LAZY)
	@JsonIgnore
	private List<Bid> bids;
}