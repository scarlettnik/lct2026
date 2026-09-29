package ru.lct.teplokontur.domain;import java.util.*;
public class ValidationReport {public boolean passed;public final List<String> errors=new ArrayList<>();public final List<String> warnings=new ArrayList<>();public void error(String e){errors.add(e);passed=false;}public void finish(){passed=errors.isEmpty();}}
