package edu.utdallas.hltri.data.clinical_trials;

import edu.utdallas.hltri.trec.pm.Expandable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import edu.utdallas.hltri.trec.pm.MedicalProblem;

public class AnalyzedTrial extends JaxbClinicalTrial {
  private final JaxbClinicalTrial trial;

  private final List<MedicalProblem> analyzedConditions;

  private final List<Expandable> analyzedInterventions;

  public AnalyzedTrial(JaxbClinicalTrial trial) {
    super(trial.docId, trial.study);
    this.trial = trial;
    this.analyzedConditions = new ArrayList<>();
    for (String condition : trial.getConditions()) {
      this.analyzedConditions.add(new MedicalProblem(condition));
    }
    this.analyzedInterventions = new ArrayList<>();
    for (Intervention intervention : trial.getInterventions()) {
      this.analyzedInterventions.add(new Expandable(intervention.getName()));
    }
  }

  public @Nonnull Optional<String> getMasking() {
    return trial.getMasking();
  }

  public @Nonnull List<MedicalProblem> getAnalyzedConditions() {
    return analyzedConditions;
  }

  public @Nonnull List<Expandable> getAnalyzedInterventions() {
    return analyzedInterventions;
  }
}
