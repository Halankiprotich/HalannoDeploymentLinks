package com.tracker;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "deployments")
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String personName;

    @Column(nullable = false)
    private String appName;

    @Column(nullable = false, length = 1000)
    private String link;

    /** GitHub account used to deploy. Nullable so older records still load. */
    @Column(length = 255)
    private String githubName;

    @Column(nullable = false)
    private LocalDate deployedDate;

    @Column(nullable = false)
    private String deployEmail;

    /** AES-GCM encrypted, never stored in plain text. */
    @Column(nullable = false, length = 2000)
    private String emailPasswordEnc;

    /** The exact end date: deployed date + 1 month. */
    public LocalDate getEndDate() {
        return deployedDate.plusMonths(1);
    }

    public Long getId() { return id; }
    public String getPersonName() { return personName; }
    public void setPersonName(String personName) { this.personName = personName; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public String getGithubName() { return githubName; }
    public void setGithubName(String githubName) { this.githubName = githubName; }
    public LocalDate getDeployedDate() { return deployedDate; }
    public void setDeployedDate(LocalDate deployedDate) { this.deployedDate = deployedDate; }
    public String getDeployEmail() { return deployEmail; }
    public void setDeployEmail(String deployEmail) { this.deployEmail = deployEmail; }
    public String getEmailPasswordEnc() { return emailPasswordEnc; }
    public void setEmailPasswordEnc(String emailPasswordEnc) { this.emailPasswordEnc = emailPasswordEnc; }
}