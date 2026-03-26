package in.BidPilots.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@Entity
@Table(name = "states")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class State {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "state_name", unique = true, nullable = false, length = 100)
	private String stateName;

	@Column(name = "is_active")
	private Boolean isActive = true;

	@Column(name = "is_deactive")
	private Boolean isDeactive = false;

	@OneToMany(mappedBy = "state", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	@JsonIgnore
	private List<City> cities;

	@OneToMany(mappedBy = "state", fetch = FetchType.LAZY)
	@JsonIgnore
	private List<Bid> bids;
}