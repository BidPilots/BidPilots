package in.BidPilots.controller;

import in.BidPilots.entity.City;
import in.BidPilots.entity.State;
import in.BidPilots.repository.CityRepository;
import in.BidPilots.repository.StateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/states-cities")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class StateCityController {

	private final StateRepository stateRepository;
	private final CityRepository cityRepository;

	// ========== STATE ENDPOINTS ==========

	@GetMapping("/status")
	public ResponseEntity<Map<String, Object>> getStatus() {
		log.info("Fetching states status");

		Map<String, Object> response = new HashMap<>();

		try {
			List<State> states = stateRepository.findAll();

			List<Map<String, Object>> stateDetails = states.stream().map(state -> {
				Map<String, Object> stateInfo = new HashMap<>();
				stateInfo.put("id", state.getId());
				stateInfo.put("name", state.getStateName());
				stateInfo.put("cityCount", cityRepository.countByState(state));
				return stateInfo;
			}).collect(Collectors.toList());

			response.put("success", true);
			response.put("statesPopulated", !states.isEmpty());
			response.put("totalStates", states.size());
			response.put("totalCities", cityRepository.count());
			response.put("stateDetails", stateDetails);

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching status: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch status: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	@GetMapping("/states")
	public ResponseEntity<Map<String, Object>> getAllStates() {
		log.info("Fetching all states");

		Map<String, Object> response = new HashMap<>();

		try {
			List<State> states = stateRepository.findAll();

			List<Map<String, Object>> stateList = states.stream().map(state -> {
				Map<String, Object> stateMap = new HashMap<>();
				stateMap.put("id", state.getId());
				stateMap.put("name", state.getStateName());
				stateMap.put("isActive", state.getIsActive());
				stateMap.put("cityCount", cityRepository.countByState(state));
				return stateMap;
			}).collect(Collectors.toList());

			response.put("success", true);
			response.put("states", stateList);
			response.put("total", stateList.size());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching states: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch states: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	@GetMapping("/state/{id}")
	public ResponseEntity<Map<String, Object>> getStateById(@PathVariable Long id) {
		log.info("Fetching state by ID: {}", id);

		Map<String, Object> response = new HashMap<>();

		try {
			State state = stateRepository.findById(id).orElse(null);

			if (state == null) {
				response.put("success", false);
				response.put("message", "State not found");
				return ResponseEntity.status(404).body(response);
			}

			Map<String, Object> stateMap = new HashMap<>();
			stateMap.put("id", state.getId());
			stateMap.put("name", state.getStateName());
			stateMap.put("isActive", state.getIsActive());
			stateMap.put("cityCount", cityRepository.countByState(state));

			response.put("success", true);
			response.put("state", stateMap);

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching state: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch state: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	// ========== CITY ENDPOINTS ==========

	@GetMapping("/cities/{stateId}")
	public ResponseEntity<Map<String, Object>> getCitiesByState(@PathVariable Long stateId) {
		log.info("Fetching cities for state ID: {}", stateId);

		Map<String, Object> response = new HashMap<>();

		try {
			List<City> cities = cityRepository.findByStateId(stateId);

			List<Map<String, Object>> cityList = cities.stream().map(city -> {
				Map<String, Object> cityMap = new HashMap<>();
				cityMap.put("id", city.getId());
				cityMap.put("name", city.getCityName());
				cityMap.put("stateId", city.getState().getId());
				cityMap.put("stateName", city.getState().getStateName());
				return cityMap;
			}).collect(Collectors.toList());

			response.put("success", true);
			response.put("cities", cityList);
			response.put("total", cityList.size());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching cities: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch cities: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	@GetMapping("/cities/active/{stateId}")
	public ResponseEntity<Map<String, Object>> getActiveCitiesByState(@PathVariable Long stateId) {
		log.info("Fetching active cities for state ID: {}", stateId);

		Map<String, Object> response = new HashMap<>();

		try {
			List<City> cities = cityRepository.findActiveCitiesByState(stateId);

			List<Map<String, Object>> cityList = cities.stream().map(city -> {
				Map<String, Object> cityMap = new HashMap<>();
				cityMap.put("id", city.getId());
				cityMap.put("name", city.getCityName());
				return cityMap;
			}).collect(Collectors.toList());

			response.put("success", true);
			response.put("cities", cityList);
			response.put("total", cityList.size());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching active cities: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch cities: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	@GetMapping("/cities")
	public ResponseEntity<Map<String, Object>> getAllCities() {
		log.info("Fetching all cities");

		Map<String, Object> response = new HashMap<>();

		try {
			List<City> cities = cityRepository.findAll();

			List<Map<String, Object>> cityList = cities.stream().map(city -> {
				Map<String, Object> cityMap = new HashMap<>();
				cityMap.put("id", city.getId());
				cityMap.put("name", city.getCityName());
				cityMap.put("stateId", city.getState().getId());
				cityMap.put("stateName", city.getState().getStateName());
				return cityMap;
			}).collect(Collectors.toList());

			response.put("success", true);
			response.put("cities", cityList);
			response.put("total", cityList.size());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching all cities: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch cities: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	@GetMapping("/city/{id}")
	public ResponseEntity<Map<String, Object>> getCityById(@PathVariable Long id) {
		log.info("Fetching city by ID: {}", id);

		Map<String, Object> response = new HashMap<>();

		try {
			City city = cityRepository.findById(id).orElse(null);

			if (city == null) {
				response.put("success", false);
				response.put("message", "City not found");
				return ResponseEntity.status(404).body(response);
			}

			Map<String, Object> cityMap = new HashMap<>();
			cityMap.put("id", city.getId());
			cityMap.put("name", city.getCityName());
			cityMap.put("stateId", city.getState().getId());
			cityMap.put("stateName", city.getState().getStateName());
			cityMap.put("isActive", city.getIsActive());

			response.put("success", true);
			response.put("city", cityMap);

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("Error fetching city: {}", e.getMessage(), e);
			response.put("success", false);
			response.put("message", "Failed to fetch city: " + e.getMessage());
			return ResponseEntity.badRequest().body(response);
		}
	}

	@GetMapping("/health")
	public ResponseEntity<Map<String, String>> healthCheck() {
		Map<String, String> response = new HashMap<>();
		response.put("status", "UP");
		response.put("service", "State-City Service");
		response.put("totalStates", String.valueOf(stateRepository.count()));
		response.put("totalCities", String.valueOf(cityRepository.count()));
		response.put("timestamp", java.time.LocalDateTime.now().toString());
		return ResponseEntity.ok(response);
	}
}