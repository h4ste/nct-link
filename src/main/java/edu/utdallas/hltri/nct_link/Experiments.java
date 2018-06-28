package edu.utdallas.hltri.nct_link;

import java.nio.file.Path;
import java.util.List;

import edu.utdallas.hltri.data.clinical_trials.ClinicalTrialSearchEngine;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;
import edu.utdallas.hltri.inquire.eval.QRels;

public class Experiments {

  public static List<JaxbClinicalTrial> getTrialsWithPmids(final QRels qrels) {
    final ClinicalTrialSearchEngine<JaxbClinicalTrial> nctSearch = ClinicalTrialSearchEngine.getLazy();
    return nctSearch.getNctsByIds(qrels.getTopics());
  }

  public static List<JaxbClinicalTrial> getTrialsWithPmids(final Path qrelsPath) {
    return getTrialsWithPmids(QRels.fromFile(qrelsPath));
  }
}
