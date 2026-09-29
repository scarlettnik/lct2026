package ru.lct.teplokontur.persistence;
import javax.persistence.*;import java.time.Instant;
@Entity @Table(name="optimization_run")
public class RunEntity { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long scenarioId; public String mode; public String status; public int progress; public String phase; public Instant createdAt=Instant.now(); @Lob public String resultPath; @Lob public String summaryJson; @Lob public String error; }
