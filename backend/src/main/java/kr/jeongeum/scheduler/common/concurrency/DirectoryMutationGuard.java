package kr.jeongeum.scheduler.common.concurrency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** First database lock for directory, invitations and signup mutations; held until commit. */
@Repository
public class DirectoryMutationGuard {
    private final JdbcTemplate jdbc;
    public DirectoryMutationGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void lock() {
        jdbc.queryForObject("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE", Integer.class);
    }
}
