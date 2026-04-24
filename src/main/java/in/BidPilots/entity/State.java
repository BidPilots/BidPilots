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

	/**
	 * Demand-driven scraping flag.
	 *
	 * Set to TRUE the first time any user saves a filter that includes this state.
	 * GeMScrapingService will only scrape states where is_demanded = true, so the
	 * system ignores the ~30+ states that no user has ever expressed interest in.
	 *
	 * Never reset to false — once a state is demanded it stays demanded, because
	 * old matches in matched_bids still reference bids from that state.
	 *
	 * DB migration: ALTER TABLE states ADD COLUMN is_demanded BOOLEAN DEFAULT FALSE;
	 *               CREATE INDEX idx_states_demanded ON states(is_demanded);
	 */
	@Column(name = "is_demanded")
	private Boolean isDemanded = false;

	@OneToMany(mappedBy = "state", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	@JsonIgnore
	private List<City> cities;

	@OneToMany(mappedBy = "state", fetch = FetchType.LAZY)
	@JsonIgnore
	private List<Bid> bids;
}