package in.BidPilots.dto.UserLogin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * PRODUCTION FIX — ResetPasswordRequestDTO
 *
 * The original DTO had an "otp" field, which meant the client was expected to
 * send the raw OTP back to the server in the reset-password request. This is
 * insecure because:
 *   1. The OTP was already consumed during /verify-reset-otp (single-use).
 *   2. Sending the OTP in a second request means clients could skip the verify
 *      step entirely and POST any 6-digit value, bypassing verification.
 *
 * FIX: Replace "otp" with "resetToken" — a server-issued UUID that is:
 *   - Only issued after OTP verification succeeds
 *   - Valid for 10 minutes
 *   - Single-use (deleted from tokenStorage on first use)
 *   - Never meaningful outside the server (no brute-force value)
 *
 * Frontend migration:
 *   On successful /verify-reset-otp response, read response.resetToken
 *   and include it in the /reset-password request body as "resetToken".
 *   Remove any code that forwards the OTP value.
 */
@Data
public class ResetPasswordRequestDTO {

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email is required")
    private String email;

    /** Server-issued UUID from /verify-reset-otp. Valid 10 minutes, single-use. */
    @NotBlank(message = "Reset token is required")
    private String resetToken;

    @NotBlank(message = "New password is required")
    private String newPassword;

    @NotBlank(message = "Please confirm your new password")
    private String confirmPassword;

    public boolean isPasswordMatch() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }
}