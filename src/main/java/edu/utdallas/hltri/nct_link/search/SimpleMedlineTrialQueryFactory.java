package edu.utdallas.hltri.nct_link.search;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import edu.emory.mathcs.backport.java.util.Collections;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.trec.pm.Expandable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.spans.SpanBoostQuery;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import edu.utdallas.hltri.conf.Config;
import edu.utdallas.hltri.data.clinical_trials.AnalyzedTrial;
import edu.utdallas.hltri.data.clinical_trials.Intervention;
import edu.utdallas.hltri.data.clinical_trials.jaxb.InvestigatorStruct;
import edu.utdallas.hltri.data.medline.MedlineSearchEngine;
import edu.utdallas.hltri.inquire.lucene.LuceneStreamUtils;
import edu.utdallas.hltri.inquire.lucene.LuceneUtils;
import edu.utdallas.hltri.struct.Weighted;
import edu.utdallas.hltri.trec.pm.LuceneSearchSettings;
import edu.utdallas.hltri.trec.pm.MedicalProblem;
import edu.utdallas.hltri.trec.pm.TrecSettings;
import edu.utdallas.hltri.trec.pm.search.query.LuceneTermQueryFactory;
import edu.utdallas.hltri.util.CollectionUtils;

import static com.hp.hpl.jena.sparql.vocabulary.VocabTestQuery.query;
import static edu.utdallas.hltri.data.medline.MedlineSearchEngine.MESH_TERMS_FIELD;

public class SimpleMedlineTrialQueryFactory {
  private final Logger log = Logger.get(MedlineTrialQueryFactory.class);

  private final MedlineSearchEngine<?> medline;


  public SimpleMedlineTrialQueryFactory() {
    this.medline = MedlineSearchEngine.getJaxbLazy();
  }

  public Query getQuery(AnalyzedTrial trial) {
    return new MedlineTrialQueryBuilder(trial).build();
  }

  public class MedlineTrialQueryBuilder {
    private final AnalyzedTrial trial;

    public MedlineTrialQueryBuilder(AnalyzedTrial trial) {
      this.trial = trial;
    }

    public Collection<String> getNctId() {
      return Collections.singleton(trial.getNctId());
    }

    public Collection<String> getAffiliations() {
      return trial.getAllInvestigators().stream()
          .map(InvestigatorStruct::getAffiliation)
          .filter(s -> !Strings.isNullOrEmpty(s))
          .collect(Collectors.toSet());
    }

    public Collection<String> getInvestigators() {
      return trial.getAllInvestigators().stream()
          .flatMap(is -> Stream.of(is.getFirstName(), is.getMiddleName(), is.getLastName()))
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());
    }

    public Collection<String> getConditions() {
      return trial.getAnalyzedConditions().stream()
          .map(Expandable::toString)
          .collect(Collectors.toSet());
    }

    public Collection<String> getMeshTerms() {
      return trial.getMeshTerms();
    }

    public Collection<String> getKeywords() {
      return trial.getKeywords();
    }


    public Collection<String> getInterventions() {
      return trial.getAnalyzedInterventions().stream()
          .map(Expandable::toString)
          .collect(Collectors.toSet());
    }

    public Query build() {
      final String text = Stream.of(getMeshTerms(), getConditions(), getInterventions(),
          getKeywords(), getMeshTerms(), getAffiliations(), getInvestigators(), getNctId())
          .flatMap(Collection::stream)
          .collect(Collectors.joining(" "));

      final BooleanQuery.Builder bq = new BooleanQuery.Builder();
      for (String field : new String[]{"abstract", "title", "authors", "investigators", "author_affiliations", "investigator_affiliations"}) {
        Collection<String> tokens = new HashSet<>(medline.tokenize(field, text));
        for (String token : tokens) {
          bq.add(new TermQuery(new Term(field, token)), Occur.SHOULD);
        }
      }

      final Query q = bq.build();
      log.debug("Generated query {}", q);

      return q;
    }
  }
}
