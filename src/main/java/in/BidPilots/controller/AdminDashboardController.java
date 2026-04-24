package in.BidPilots.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardController {

	/**
	 * Serve the admin dashboard page. Auth is enforced by Spring Security — no
	 * manual Authentication check needed.
	 */
	@GetMapping("/dashboard")
	public ModelAndView showAdminDashboard() {
		log.info("📊 Serving admin dashboard");
		return new ModelAndView("admin/adminDashboard");
	}

	/**
	 * Serves the BOQ Manager HTML page.
	 */
	@GetMapping("/boq-manager")
	public String boqManagerPage() {
		return "admin/AdminBOQ";
	}

	@ModelAttribute("currentUri")
	public String currentUri(HttpServletRequest request) {
		return request.getRequestURI();
	}
}