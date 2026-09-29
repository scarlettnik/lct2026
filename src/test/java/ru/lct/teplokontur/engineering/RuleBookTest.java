package ru.lct.teplokontur.engineering;
import org.junit.jupiter.api.Test;import ru.lct.teplokontur.domain.*;import static org.junit.jupiter.api.Assertions.*;
class RuleBookTest {
 @Test void selectsMinimumDn(){assertEquals(125,RuleBook.minForFlow(40.2).dn);assertEquals(150,RuleBook.minForFlow(40.21).dn);assertEquals(300,RuleBook.minForFlow(300).dn);}
 @Test void officialScore(){assertEquals(1.0,RuleBook.officialScore(25_000_000,100),1e-12);}
 @Test void depthFactor(){assertEquals(1,RuleBook.depthFactor(2.2),1e-12);assertEquals(1.05,RuleBook.depthFactor(3.5),1e-12);}
}
