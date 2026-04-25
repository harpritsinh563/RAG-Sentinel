package com.ragsentinel.service.seeder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {
    private final VectorStore vectorStore;
    private Logger log = LoggerFactory.getLogger(DataSeeder.class);

    public DataSeeder(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws Exception {
        // Seeding the pgvector database on startup
        vectorStore.add(List.of(
                new Document("Our company policy states that refunds are processed within 7 business days, and require manager approval for amounts over $500.")
        ));
       log.info("Vector DB Seeded with Company Policies!");
    }
}
