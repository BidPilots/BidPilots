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

	/**
	 * Demand-driven scraping flag.
	 *
	 * Set to TRUE the first time any user saves a filter that includes this city.
	 * GeMScrapingService will skip cities where is_demanded = false, dramatically
	 * reducing the number of state-city combinations scraped from GeM portal.
	 *
	 * Never reset to false — once a city is demanded it stays demanded.
	 *
	 * DB migration: ALTER TABLE cities ADD COLUMN is_demanded BOOLEAN DEFAULT FALSE;
	 *               CREATE INDEX idx_cities_demanded ON cities(is_demanded);
	 */
	@Column(name = "is_demanded")
	private Boolean isDemanded = false;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "state_id", nullable = false)
	@JsonIgnoreProperties({ "cities", "bids" })
	private State state;

	@OneToMany(mappedBy = "consigneeCity", fetch = FetchType.LAZY)
	@JsonIgnore
	private List<Bid> bids;
}