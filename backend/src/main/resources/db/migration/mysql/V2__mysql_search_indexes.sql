ALTER TABLE studies ADD FULLTEXT INDEX ft_studies_research
    (title, abstract_text, problem_statement, methodology, features_text, keywords_text);

ALTER TABLE document_segments ADD FULLTEXT INDEX ft_document_segments (extracted_text);
