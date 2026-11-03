package com.tracker;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class DeploymentController {

    /** Only these two GitHub accounts are allowed. */
    private static final String GITHUB_ALLOWED = "Alimaseidu|Halankiprotich";
    private static final String GITHUB_MESSAGE = "GitHub name must be Alimaseidu or Halankiprotich";

    private static final String PHONE_REGEX = "^\\+?[0-9][0-9 ()\\-]{5,18}$";
    private static final String PHONE_MESSAGE = "Enter a valid phone number (digits, spaces, + and - are allowed)";

    /**
     * One request used for both adding and editing.
     * emailPassword may be empty when editing (empty = keep the saved password).
     */
    public record DeploymentRequest(
            @NotBlank(message = "Person name is required") String personName,
            @NotBlank(message = "App name is required") String appName,
            @NotBlank(message = "Link is required") String link,
            @NotBlank(message = GITHUB_MESSAGE) @Pattern(regexp = GITHUB_ALLOWED, message = GITHUB_MESSAGE) String githubName,
            @NotBlank(message = "Phone number is required") @Pattern(regexp = PHONE_REGEX, message = PHONE_MESSAGE) String phoneNumber,
            @NotBlank(message = "Deploy email is required") @Email(message = "Enter a valid email") String deployEmail,
            String emailPassword,
            @NotNull(message = "Deployed date is required") LocalDate deployedDate,
            @NotBlank(message = "Choose a payment status")
            @Pattern(regexp = "FULL|PARTIAL", message = "Payment status must be Fully paid or Partial") String paymentStatus,
            BigDecimal totalAmount,
            BigDecimal amountPaid,
            LocalDate clearDate) {}

    public record RenewRequest(@NotNull LocalDate deployedDate) {}

    public record DeploymentResponse(Long id, String personName, String appName, String link,
                                     String githubName, String phoneNumber, String deployEmail,
                                     LocalDate deployedDate, LocalDate endDate,
                                     String paymentStatus, BigDecimal totalAmount,
                                     BigDecimal amountPaid, BigDecimal balance, LocalDate clearDate) {
        static DeploymentResponse of(Deployment d) {
            BigDecimal balance = null;
            if ("PARTIAL".equals(d.getPaymentStatus()) && d.getTotalAmount() != null && d.getAmountPaid() != null) {
                balance = d.getTotalAmount().subtract(d.getAmountPaid());
            }
            return new DeploymentResponse(d.getId(), d.getPersonName(), d.getAppName(), d.getLink(),
                    d.getGithubName(), d.getPhoneNumber(), d.getDeployEmail(),
                    d.getDeployedDate(), d.getEndDate(),
                    d.getPaymentStatus(), d.getTotalAmount(), d.getAmountPaid(), balance, d.getClearDate());
        }
    }

    /** An error with an HTTP status and a message that is shown in the form. */
    static class ApiException extends RuntimeException {
        final HttpStatus status;
        ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final DeploymentRepository repo;
    private final CryptoService crypto;

    public DeploymentController(DeploymentRepository repo, CryptoService crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    /** Makes links comparable: ignores case, http/https, "www." and trailing slashes. */
    private static String normalize(String link) {
        return link.trim().toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "")
                .replaceAll("/+$", "");
    }

    /** Throws if another record (not selfId) already uses this link. */
    private void ensureLinkIsNew(String link, Long selfId) {
        String wanted = normalize(link);
        repo.findAll().stream()
                .filter(d -> !d.getId().equals(selfId))
                .filter(d -> normalize(d.getLink()).equals(wanted))
                .findFirst()
                .ifPresent(d -> {
                    throw new ApiException(HttpStatus.CONFLICT, "This link is already saved under "
                            + d.getAppName() + " (" + d.getPersonName() + ")");
                });
    }

    private void validatePayment(DeploymentRequest r) {
        BigDecimal total = r.totalAmount();
        BigDecimal paid = r.amountPaid();
        if (total != null && total.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Total amount must be more than 0");
        }
        if ("PARTIAL".equals(r.paymentStatus())) {
            if (paid == null || paid.signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the amount paid so far");
            }
            if (r.clearDate() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Choose the date the balance must be cleared");
            }
            if (total != null && paid.compareTo(total) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "The amount paid is not less than the total. Choose Fully paid instead");
            }
        }
    }

    private void apply(Deployment d, DeploymentRequest r) {
        d.setPersonName(r.personName().trim());
        d.setAppName(r.appName().trim());
        d.setLink(r.link().trim());
        d.setGithubName(r.githubName());
        d.setPhoneNumber(r.phoneNumber().trim());
        d.setDeployEmail(r.deployEmail().trim());
        d.setDeployedDate(r.deployedDate());
        d.setPaymentStatus(r.paymentStatus());
        if ("PARTIAL".equals(r.paymentStatus())) {
            d.setTotalAmount(r.totalAmount());
            d.setAmountPaid(r.amountPaid());
            d.setClearDate(r.clearDate());
        } else {
            d.setTotalAmount(r.totalAmount());
            d.setAmountPaid(null);
            d.setClearDate(null);
        }
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @GetMapping("/api/deployments")
    public List<DeploymentResponse> list() {
        return repo.findAll(Sort.by("deployedDate").ascending()).stream()
                .map(DeploymentResponse::of).toList();
    }

    @PostMapping("/api/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentResponse create(@Valid @RequestBody DeploymentRequest r) {
        if (r.emailPassword() == null || r.emailPassword().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email password is required");
        }
        validatePayment(r);
        ensureLinkIsNew(r.link(), null);
        Deployment d = new Deployment();
        apply(d, r);
        d.setEmailPasswordEnc(crypto.encrypt(r.emailPassword()));
        return DeploymentResponse.of(repo.save(d));
    }

    /** Edit a record. The countdown carries on unless a different deployed date is sent. */
    @PutMapping("/api/deployments/{id}")
    public ResponseEntity<DeploymentResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody DeploymentRequest r) {
        return repo.findById(id).map(d -> {
            validatePayment(r);
            ensureLinkIsNew(r.link(), id);
            apply(d, r);
            if (r.emailPassword() != null && !r.emailPassword().isBlank()) {
                d.setEmailPasswordEnc(crypto.encrypt(r.emailPassword()));
            }
            return ResponseEntity.ok(DeploymentResponse.of(repo.save(d)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/deployments/{id}/renew")
    public ResponseEntity<DeploymentResponse> renew(@PathVariable Long id, @Valid @RequestBody RenewRequest r) {
        return repo.findById(id).map(d -> {
            d.setDeployedDate(r.deployedDate());
            return ResponseEntity.ok(DeploymentResponse.of(repo.save(d)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/deployments/{id}/password")
    public ResponseEntity<Map<String, String>> password(@PathVariable Long id) {
        return repo.findById(id)
                .map(d -> ResponseEntity.ok(Map.of("password", crypto.decrypt(d.getEmailPasswordEnc()))))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/deployments/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage()).findFirst().orElse("Invalid input");
        return Map.of("error", msg);
    }
}