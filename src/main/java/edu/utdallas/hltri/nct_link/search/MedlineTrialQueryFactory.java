package edu.utdallas.hltri.nct_link.search;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Streams;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.trec.pm.Expandable;
import java.util.Arrays;
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

public class MedlineTrialQueryFactory {
  private final Logger log = Logger.get(MedlineTrialQueryFactory.class);

  private final MedlineSearchEngine medline;
  private final LuceneTermQueryFactory termQueryFactory;

  private final Splitter affiliationSegmenter = Splitter.on(',').omitEmptyStrings().trimResults();

  public MedlineTrialQueryFactory() {
    this.medline = MedlineSearchEngine.getJaxbLazy();
    final LuceneSearchSettings settings = TrecSettings.INSTANCE.MEDLINE.cloneWithFieldWeights(
        Config.load("nct_link").getDoubleMap("search.field-weights")
    );
    this.termQueryFactory = new LuceneTermQueryFactory(this.medline, settings);
    log.debug("Initialized Medline-Trial Query Factory with settings: {}", settings);
  }

  public Query getQuery(AnalyzedTrial trial) {
    return new MedlineTrialQueryBuilder(trial).build();
  }

  private static final Object nctKey = new Object();
  private static final Object meshKey = new Object();
  private static final Object keywordsKey = new Object();
  private static final Object jointKey = new Object();
  private static final String[] authorFields = new String[] { "authors", "investigators" };
  private static final String[] affiliationFields = new String[] { "author_affiliations", "investigator_affiliations" };

  public class MedlineTrialQueryBuilder {
    private final AnalyzedTrial trial;

    private final Map<Object, Query> queryCache = new HashMap<>();

    private final Splitter splitter = Splitter.on(CharMatcher.whitespace())
        .omitEmptyStrings()
        .trimResults();
    private final Joiner joiner = Joiner.on(' ');

    public MedlineTrialQueryBuilder(AnalyzedTrial trial) {
      this.trial = trial;
    }

    public Query getNctIdQuery() {
      return queryCache.computeIfAbsent(nctKey, key -> {
        final BooleanQuery.Builder bqb = new BooleanQuery.Builder();
        bqb.add(new TermQuery(new Term("abstract", trial.getNctId().toLowerCase())), Occur.SHOULD);
        bqb.add(new TermQuery(new Term("registered_trials", trial.getNctId())), Occur.SHOULD);
        final Query q = bqb.build();
        log.trace("created NCT ID query {}", q);
        return q;
      });
    }

    public List<Query> getAffiliationsQueries() {
      final Set<String> affiliations = trial.getAllInvestigators().stream()
          .map(InvestigatorStruct::getAffiliation)
          .filter(s -> !Strings.isNullOrEmpty(s))
          .collect(Collectors.toSet());

      final List<Query> queries = new ArrayList<>();
      for (final String affiliation : affiliations) {
        final List<Query> disjuncts = new ArrayList<>();
        final List<String> segments = affiliationSegmenter.splitToList(affiliation);
        for (final String field : affiliationFields) {
          final List<Query> segmentQueries = new ArrayList<>();
          for (final String segment : segments) {
            final Query query = queryCache.computeIfAbsent(segment,
                key -> medline.newPhraseQuery(field, affiliation)
            );
            segmentQueries.add(query);
          }
          if (!segments.isEmpty()) {
            disjuncts.add(LuceneUtils.makeBooleanQuery(segmentQueries, Occur.SHOULD));
          }
        }
        final int nDisjuncts = disjuncts.size();
        if (nDisjuncts > 1) {
          queries.add(new DisjunctionMaxQuery(disjuncts, 0.01f));
        } else if (disjuncts.size() == 1) {
          queries.add(disjuncts.get(0));
        } else {
          log.warn("Unable to make query for affiliation |{}|", affiliation);
        }
      }

      return queries;
    }

    public final Optional<Query> getAffiliationsQuery() {
      final List<Query> affiliationsQueries = getAffiliationsQueries();
      if (affiliationsQueries.isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(affiliationsQueries.stream()
          .collect(LuceneStreamUtils.toBooleanQuery(Occur.SHOULD)));
      }
    }

    private SpanBoostQuery getInvestigatorNameQuery(String name, String field, float weight) {
      final List<SpanTermQuery> clauses = LuceneStreamUtils.streamTerms(
              name,
              field,
              medline.getAnalyzer())
          .map(SpanTermQuery::new)
          .collect(Collectors.toList());

      SpanQuery query;
      if (clauses.size() > 1) {
        query = new SpanNearQuery(
            clauses.toArray(new SpanQuery[clauses.size()]),
            1,
            true);
      } else if (clauses.size() == 1) {
        query = clauses.get(0);
      } else {
        log.error("Unable to make span query for name |{}|", name);
        query = new SpanTermQuery(new Term(field, name));
      }

      return new SpanBoostQuery(query, weight);
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Query getInvestigatorFieldQuery(String field,
        Optional<String> firstName, Optional<Character> firstInitial,
        Optional<Character> middleInitial,
        String lastName) {
      // Store multiple queries for each name
      final List<Query> subQueries = new ArrayList<>();
      final StringBuilder sb = new StringBuilder();

      // Try "firstName middleInitial lastName"
      if (firstName.isPresent() && middleInitial.isPresent()) {
        firstName.map(name -> sb.append(name).append(' '));
        middleInitial.map(initial -> sb.append((char) initial).append(' '));
        sb.append(lastName);
        subQueries.add(getInvestigatorNameQuery(sb.toString(), field, 5f));
      }

      // Try "firstName lastName"
      if (firstName.isPresent()) {
        sb.setLength(0);
        firstName.map(name -> sb.append(name).append(' '));
        sb.append(lastName);
        subQueries.add(getInvestigatorNameQuery(sb.toString(), field, 3f));
      }

      // Try "firstInitial+middleInitial lastName"
      if (firstInitial.isPresent() && middleInitial.isPresent()) {
        sb.setLength(0);
        firstInitial.map(initial -> sb.append((char) initial));
        middleInitial.map(initial -> sb.append((char) initial));
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append(lastName);
        subQueries.add(getInvestigatorNameQuery(sb.toString(), field + "_initials", 2f));
      }

      // Try "firstInitial lastName"
      if (firstInitial.isPresent()) {
        sb.setLength(0);
        firstInitial.map(initial -> sb.append((char) initial).append(' '));
        sb.append(lastName);
        subQueries.add(getInvestigatorNameQuery(sb.toString(), field + "_initials", 1f));
      }

      // Try "lastName"
      sb.setLength(0);
      sb.append(lastName);
      subQueries.add(getInvestigatorNameQuery(sb.toString(), field, .1f));

      if (subQueries.size() > 1) {
        return new DisjunctionMaxQuery(subQueries, 0.001f);
      } else {
        return subQueries.get(0);
      }
    }

    private Optional<Query> getInvestigatorQuery(InvestigatorStruct investigator) {
      String lastName = investigator.getLastName();

      // Skip investigators without a last name
      if (lastName == null) {
        return Optional.empty();
      }

      // Throw away anything after a comma (e.g., Bob Dole, PhD)
      int lastNameComma = lastName.indexOf(',');
      if (lastNameComma > -1) {
        lastName = lastName.substring(0, lastNameComma);
      }

      // Remove useless white space
      lastName = lastName.trim();

      // Check if lastName is empty after previous steps
      if (lastName.isEmpty()) {
        return Optional.empty();
      }

      // Parse fields
      Optional<String> firstName = Optional.ofNullable(investigator.getFirstName()).map(String::trim);
      Optional<Character> firstInitial = firstName.map(name -> name.charAt(0));
      Optional<Character> middleInitial = Optional.ofNullable(investigator.getMiddleName())
          .map(String::trim)
          .map(name -> name.charAt(0));

      // Sometimes, names are encoded as FirstName: NULL, MiddleName: NULL, LastName: FIRST MIDDLE LAST, PhD...
      if (!firstName.isPresent() && !middleInitial.isPresent() && lastName.indexOf(' ') > -1) {
        final List<String> names = splitter.splitToList(lastName);
        if (names.size() > 3) {
          log.warn("Failed to parse last name |{}|", lastName);
        }
        firstName = Optional.of(names.get(0));
        firstInitial = Optional.of(names.get(0).charAt(0));

        // If we have room for a middle name, parse it
        if (names.size() > 2) {
          middleInitial = Optional.of(names.get(1).charAt(0));
          lastName = joiner.join(names.subList(2, names.size()));
        } else {
          lastName = joiner.join(names.subList(1, names.size()));
        }
      }

      // Check if lastName is empty, again...
      if (lastName.isEmpty()) {
        return Optional.empty();
      }

      final List<Query> clauses = new ArrayList<>();
      for (String field : authorFields) {
        clauses.add(getInvestigatorFieldQuery(field, firstName, firstInitial, middleInitial, lastName));
      }
      if (clauses.isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(new DisjunctionMaxQuery(clauses, 0.01f));
      }
    }

    public List<Query> getInvestigatorsQueries() {
      final Collection<InvestigatorStruct> investigators = trial.getAllInvestigators();
      List<Query> queries = new ArrayList<>(investigators.size());
      for (final InvestigatorStruct investigator : investigators) {
        Optional.ofNullable(
            queryCache.computeIfAbsent(
                investigator,
                key -> {
                  final Query q = getInvestigatorQuery(investigator).orElse(null);
                  log.trace("for investigator {} {} {}, created query {}", investigator.getFirstName(),
                      investigator.getMiddleName(), investigator.getLastName(), q);
                  return q;
                }
            )
        ).ifPresent(queries::add);
      }
      return queries;
    }

    public Optional<Query> getInvestigatorsQuery() {
      final List<Query> investigatorQueries = getInvestigatorsQueries();
      if (investigatorQueries.isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(getInvestigatorsQueries().stream()
            .collect(LuceneStreamUtils.toBooleanQuery(Occur.SHOULD)));
      }
    }

    private Query getMedicalProblemQuery(final MedicalProblem problem) {
      return queryCache.computeIfAbsent(problem, key -> termQueryFactory.buildExpandedQuery(
          problem.toString(),
          problem.getFlatExpansions()));
    }

    public Collection<Query> getConditionsQueries() {
      return trial.getAnalyzedConditions().stream()
          .map(this::getMedicalProblemQuery)
          .collect(Collectors.toList());
    }

    public Optional<Query> getConditionsQuery() {
      if (trial.getAnalyzedConditions().isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(trial.getAnalyzedConditions().stream()
            .map(this::getMedicalProblemQuery)
            .collect(LuceneUtils.toBooleanQuery(Occur.MUST)));
      }
    }

    public Optional<Query> getMeshTermsQuery() {
      if (trial.getMeshTerms().isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(queryCache.computeIfAbsent(meshKey, key -> trial.getMeshTerms().stream()
            .map(term -> termQueryFactory.tryBuildUnweightedTermFieldQuery(term, "mesh_headings"))
            .flatMap(CollectionUtils::streamOptional)
            .collect(LuceneUtils.toBooleanQuery(Occur.SHOULD))));
      }
    }

    public Optional<Query> getKeywordsQuery() {
      if (trial.getAnalyzedInterventions().isEmpty()) {
        return Optional.empty();
      } else {
        return Optional
            .of(queryCache.computeIfAbsent(keywordsKey, key -> trial.getKeywords().stream()
                .map(termQueryFactory::tryBuildUnweightedTermQuery)
                .flatMap(CollectionUtils::streamOptional)
                .collect(LuceneUtils.toBooleanQuery(Occur.SHOULD))));
      }
    }

    private Query getInterventionQuery(Expandable intervention) {
      return queryCache.computeIfAbsent(intervention, key -> {
            final Query q = termQueryFactory.buildExpandedQuery(
                intervention.toString(),
                intervention.getFlatExpansions());
            log.trace("For intervention {}, generated query {}", intervention, q);
            return q;
          }
      );
    }

    public Collection<Query> getInterventionsQueries() {
      return trial.getAnalyzedInterventions().stream()
          .map(this::getInterventionQuery)
          .collect(Collectors.toList());
    }

    public Optional<Query> getInterventionsQuery() {
      List<Query> interventionQueries = trial.getAnalyzedInterventions().stream()
          .map(this::getInterventionQuery)
          .collect(Collectors.toList());
      if (interventionQueries.isEmpty()) {
        return Optional.empty();
      } else {
        return Optional.of(LuceneUtils.makeBooleanQuery(interventionQueries, Occur.SHOULD));
      }
    }

    private Query boost(Query query, float boost) {
      return new BoostQuery(query, boost);
    }

    private Optional<Query> boost(Optional<Query> query, float boost) {
      return query.map(q -> new BoostQuery(q, boost));
    }

    public Query build() {
      final Query q = queryCache.computeIfAbsent(jointKey, key -> Stream.of(
          boost(getConditionsQuery(), 2),
          getInterventionsQuery(),
          getKeywordsQuery(),
          getMeshTermsQuery(),
          boost(getAffiliationsQuery(), 2),
          boost(getInvestigatorsQuery(), 5),
          Optional.of(boost(getNctIdQuery(), 100_000)))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(LuceneUtils.toBooleanQuery(Occur.SHOULD)));
      log.debug("Created query {}", q);
      return q;
    }
  }
}
