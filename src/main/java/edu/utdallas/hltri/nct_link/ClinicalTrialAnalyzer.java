package edu.utdallas.hltri.nct_link;

import edu.utdallas.hltri.data.clinical_trials.AnalyzedTrial;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;

public class ClinicalTrialAnalyzer {
  public AnalyzedTrial analyze(JaxbClinicalTrial trial) {
    return new AnalyzedTrial(trial);
  }
}
