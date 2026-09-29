package ru.lct.teplokontur.persistence;
import javax.persistence.*;import java.time.Instant;
@Entity @Table(name="scenario")
public class ScenarioEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 public String name; public String status; public Instant createdAt=Instant.now(); public long featureCount; public String diagnostic;
}
