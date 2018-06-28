package edu.utdallas.hltri.nct_link.search;

import com.google.common.base.Strings;
import edu.emory.mathcs.backport.java.util.Collections;
import edu.utdallas.hltri.data.clinical_trials.AnalyzedTrial;
import edu.utdallas.hltri.data.clinical_trials.jaxb.InvestigatorStruct;
import edu.utdallas.hltri.data.medline.MedlineSearchEngine;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.trec.pm.Expandable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

public class ExactMedlineTrialQueryFactory {
  private final Logger log = Logger.get(MedlineTrialQueryFactory.class);


  public ExactMedlineTrialQueryFactory() {
  }

  public Query getQuery(AnalyzedTrial trial) {
    return new MedlineTrialQueryBuilder(trial).build();
  }

  public class MedlineTrialQueryBuilder {

    private final AnalyzedTrial trial;

    public MedlineTrialQueryBuilder(AnalyzedTrial trial) {
      this.trial = trial;
    }

    public Query build() {
      final BooleanQuery.Builder bqb = new BooleanQuery.Builder();
      bqb.add(new TermQuery(new Term("abstract", trial.getNctId().toLowerCase())), Occur.SHOULD);
      bqb.add(new TermQuery(new Term("registered_trials", trial.getNctId())), Occur.SHOULD);
      bqb.add(new BoostQuery(new MatchAllDocsQuery(), 0f), Occur.SHOULD);
      final Query q = bqb.build();
      log.debug("created NCT ID query {}", q);
      return q;
    }
  }
}
