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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class DeploymentController {

    /** Only these two GitHub accounts are allowed. */
    private static final String GITHUB_ALLOWED = "Alimaseidu|Halankiprotich";
    private static final String GITHUB_MESSAGE = "GitHub name must be Alimaseidu or Halankiprotich";

    public record DeploymentRequest(
            @NotBlank(message = "Person name is required") String personName,
            @NotBlank(message = "App name is required") String appName,
            @NotBlank(message = "Link is required") String link,
            @NotBlank(message = GITHUB_MESSAGE) @Pattern(regexp = GITHUB_ALLOWED, message = GITHUB_MESSAGE) String githubName,
            @NotBlank(message = "Deploy email is required") @Email(message = "Enter a valid email") String deployEmail,
            @NotBlank(message = "Email password is required") String emailPassword,
            @NotNull(message = "Deployed date is required") LocalDate deployedDate) {}

    /** Same as create, but the password is optional (empty = keep the saved one). */
    public record DeploymentUpdate(
            @NotBlank(message = "Person name is required") String personName,
            @NotBlank(message = "App name is required") String appName,
            @NotBlank(message = "Link is required") String link,
            @NotBlank(message = GITHUB_MESSAGE) @Pattern(regexp = GITHUB_ALLOWED, message = GITHUB_MESSAGE) String githubName,
            @NotBlank(message = "Deploy email is required") @Email(message = "Enter a valid email") String deployEmail,
            String emailPassword,
            @NotNull(message = "Deployed date is required") LocalDate deployedDate) {}

    public record RenewRequest(@NotNull LocalDate deployedDate) {}

    public record DeploymentResponse(Long id, String personName, String appName, String link,
                                     String githubName, String deployEmail,
                                     LocalDate deployedDate, LocalDate endDate) {
        static DeploymentResponse of(Deployment d) {
            return new DeploymentResponse(d.getId(), d.getPersonName(), d.getAppName(), d.getLink(),
                    d.getGithubName(), d.getDeployEmail(), d.getDeployedDate(), d.getEndDate());
        }
    }

    /** Thrown when the same link is already saved. */
    static class DuplicateLinkException extends RuntimeException {
        DuplicateLinkException(String message) {
            super(message);
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
                    throw new DuplicateLinkException("This link is already saved under "
                            + d.getAppName() + " (" + d.getPersonName() + ")");
                });
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
        ensureLinkIsNew(r.link(), null);
        Deployment d = new Deployment();
        d.setPersonName(r.personName().trim());
        d.setAppName(r.appName().trim());
        d.setLink(r.link().trim());
        d.setGithubName(r.githubName());
        d.setDeployEmail(r.deployEmail().trim());
        d.setEmailPasswordEnc(crypto.encrypt(r.emailPassword()));
        d.setDeployedDate(r.deployedDate());
        return DeploymentResponse.of(repo.save(d));
    }

    /** Edit a record. The countdown carries on unless a different deployed date is sent. */
    @PutMapping("/api/deployments/{id}")
    public ResponseEntity<DeploymentResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody DeploymentUpdate r) {
        return repo.findById(id).map(d -> {
            ensureLinkIsNew(r.link(), id);
            d.setPersonName(r.personName().trim());
            d.setAppName(r.appName().trim());
            d.setLink(r.link().trim());
            d.setGithubName(r.githubName());
            d.setDeployEmail(r.deployEmail().trim());
            if (r.emailPassword() != null && !r.emailPassword().isBlank()) {
                d.setEmailPasswordEnc(crypto.encrypt(r.emailPassword()));
            }
            d.setDeployedDate(r.deployedDate());
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

    @ExceptionHandler(DuplicateLinkException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate(DuplicateLinkException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage()).findFirst().orElse("Invalid input");
        return Map.of("error", msg);
    }
}