package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramio.TestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramSummary;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.ShareTokenTakenException;
import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.domain.version.DiagramVersionSummary;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import com.diagramio.infrastructure.share.DiagramShareMapper;
import com.diagramio.infrastructure.share.DiagramShareRepositoryAdapter;
import com.diagramio.infrastructure.version.DiagramVersionRepositoryAdapter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Chạy Flyway + Hibernate (ddl-auto: validate) và các câu SQL viết tay của adapter trên PostgreSQL thật.
 * Mỗi test chạy trong một transaction và được rollback. Bỏ qua khi máy không có Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DiagramRepositoryAdapter.class, DiagramVersionRepositoryAdapter.class,
        DiagramShareRepositoryAdapter.class, DiagramShareMapper.class})
@Testcontainers(disabledWithoutDocker = true)
class JpaRepositoriesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestDatabase.POSTGRES_IMAGE);

    private static final DiagramData DATA = DiagramData.fromStorage("{\"nodes\": [{\"id\": \"n1\"}], \"edges\": []}");
    private static final DiagramData NEW_DATA = DiagramData.fromStorage("{\"nodes\": [], \"edges\": [{\"id\": \"e1\"}]}");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DiagramRepositoryAdapter diagramRepository;

    @Autowired
    private DiagramVersionRepositoryAdapter versionRepository;

    @Autowired
    private DiagramShareRepositoryAdapter shareRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** Chủ riêng cho mỗi test, để danh sách "của tôi" không lẫn sơ đồ của test khác. */
    private UUID owner;

    @BeforeEach
    void createOwner() {
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, 'x')", owner,
                "u" + owner.toString().substring(0, 8), owner + "@test.local");
    }

    // ---------- sơ đồ: đọc ----------

    @Test
    void insertAssignsIdAndAccessInfoReadsTheSameRowWithoutData() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);

        assertThat(diagram.getId()).isNotNull();
        assertThat(diagramRepository.findById(diagram.getId())).get().satisfies(d -> {
            assertSameJson(d.getData(), DATA);
            assertThat(d.getVersion()).isEqualTo(1);
        });
        DiagramAccessInfo expected = new DiagramAccessInfo(diagram.getId(), owner, false, 1);
        assertThat(diagramRepository.findAccessInfo(diagram.getId())).contains(expected);
        assertThat(diagramRepository.lockAccessInfo(diagram.getId())).contains(expected);
        assertThat(diagramRepository.findAccessInfo(UUID.randomUUID())).isEmpty();
        assertThat(diagramRepository.lockAccessInfo(UUID.randomUUID())).isEmpty();
    }

    // ---------- sơ đồ: UPDATE có điều kiện version ----------

    @Test
    void updateWithExpectedVersionWritesOnceAndReturnsTheNewState() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        Instant now = T0.plusSeconds(60);
        Diagram change = new Diagram(diagram.getId(), "Renamed", DiagramType.UML, NEW_DATA, 1, true, owner, owner,
                null, now);

        Diagram updated = diagramRepository.updateIfVersion(change, true).orElseThrow();

        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getTitle()).isEqualTo("Renamed");
        assertSameJson(updated.getData(), NEW_DATA);
        assertThat(updated.getCreatedAt()).isEqualTo(diagram.getCreatedAt());   // RETURNING lấy từ CSDL
        assertThat(updated.getUpdatedAt()).isEqualTo(now);
        Diagram stored = diagramRepository.findById(diagram.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getTitle()).isEqualTo("Renamed");
        assertThat(stored.getDiagramType()).isEqualTo(DiagramType.UML);
        assertThat(stored.isPublic()).isTrue();
        assertThat(stored.getData().json()).contains("\"e1\"");
    }

    @Test
    void staleUpdateMatchesNothingAndLeavesTheRowUnchanged() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        // Người khác đã lưu trước => CSDL lên version 2
        diagramRepository.updateIfVersion(new Diagram(diagram.getId(), "By someone else", DiagramType.ERD, DATA, 1,
                false, owner, owner, null, T0.plusSeconds(1)), true).orElseThrow();

        Optional<Diagram> stale = diagramRepository.updateIfVersion(new Diagram(diagram.getId(), "Mine",
                DiagramType.ERD, NEW_DATA, 1, false, owner, owner, null, T0.plusSeconds(2)), true);

        assertThat(stale).isEmpty();
        Diagram stored = diagramRepository.findById(diagram.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getTitle()).isEqualTo("By someone else");
        assertSameJson(stored.getData(), DATA);
    }

    @Test
    void updateOfDeletedDiagramMatchesNothing() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        diagramRepository.deleteById(diagram.getId());

        assertThat(diagramRepository.updateIfVersion(new Diagram(diagram.getId(), "x", DiagramType.ERD, DATA, 1,
                false, owner, owner, null, T0), true)).isEmpty();
        assertThat(diagramRepository.findById(diagram.getId())).isEmpty();
    }

    @Test
    void updateThatMayNotChangePrivacyKeepsTheStoredFlagAndReturnsIt() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, true, T0);

        // Người sửa qua link gửi kèm isPublic=false (lấy từ một bản cũ): CSDL giữ nguyên, RETURNING trả giá trị thật
        Diagram updated = diagramRepository.updateIfVersion(new Diagram(diagram.getId(), "Edited by link",
                DiagramType.ERD, NEW_DATA, 1, false, owner, null, null, T0.plusSeconds(1)), false).orElseThrow();

        assertThat(updated.isPublic()).isTrue();
        assertThat(updated.getVersion()).isEqualTo(2);
        Diagram stored = diagramRepository.findById(diagram.getId()).orElseThrow();
        assertThat(stored.isPublic()).isTrue();
        assertThat(stored.getTitle()).isEqualTo("Edited by link");
    }

    // ---------- danh sách ----------

    @Test
    void ownerListIsNewestFirstWithAStableTieBreakAndAnExactTotal() {
        // 5 sơ đồ, hai cặp trùng updated_at: thứ tự giữa các trang vẫn xác định nhờ id
        List<Diagram> created = new ArrayList<>();
        created.add(newDiagram("a", DiagramType.ERD, false, T0));
        created.add(newDiagram("b", DiagramType.ERD, false, T0.plusSeconds(10)));
        created.add(newDiagram("c", DiagramType.ERD, false, T0.plusSeconds(10)));
        created.add(newDiagram("d", DiagramType.UML, false, T0.plusSeconds(20)));
        created.add(newDiagram("e", DiagramType.UML, false, T0.plusSeconds(20)));

        List<UUID> pages = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            DiagramPage result = diagramRepository.findByOwner(owner, null, "", page, 2);
            assertThat(result.total()).isEqualTo(5);
            result.items().forEach(s -> pages.add(s.id()));
        }

        // Không trùng, không sót qua các trang; trong cùng một updated_at thì thứ tự do id quyết định
        assertThat(pages).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(created.stream().map(Diagram::getId).toList());
        assertThat(pages.subList(0, 2)).containsExactlyInAnyOrder(created.get(3).getId(), created.get(4).getId());
        assertThat(pages.subList(2, 4)).containsExactlyInAnyOrder(created.get(1).getId(), created.get(2).getId());
        assertThat(pages.get(4)).isEqualTo(created.get(0).getId());
        assertThat(pages).isEqualTo(diagramRepository.findByOwner(owner, null, "", 0, 5).items().stream()
                .map(DiagramSummary::id).toList());
    }

    @Test
    void ownerListMapsEverySummaryColumn() {
        Diagram diagram = newDiagram("Orders", DiagramType.UML, true, T0);

        DiagramSummary summary = diagramRepository.findByOwner(owner, null, "", 0, 10).items().get(0);

        assertThat(summary).isEqualTo(new DiagramSummary(diagram.getId(), "Orders", DiagramType.UML, 1, true, owner,
                owner, diagram.getCreatedAt(), diagram.getUpdatedAt()));
    }

    @Test
    void ownerListFiltersByTypeAndCaseInsensitiveSubstringOfTitle() {
        newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        newDiagram("shop classes", DiagramType.UML, false, T0.plusSeconds(1));
        newDiagram("Library", DiagramType.ERD, false, T0.plusSeconds(2));

        assertThat(titles(diagramRepository.findByOwner(owner, null, "SHOP", 0, 10)))
                .containsExactly("shop classes", "Shop ERD");
        assertThat(titles(diagramRepository.findByOwner(owner, DiagramType.ERD, "", 0, 10)))
                .containsExactly("Library", "Shop ERD");
        DiagramPage both = diagramRepository.findByOwner(owner, DiagramType.ERD, "shop", 0, 10);
        assertThat(titles(both)).containsExactly("Shop ERD");
        assertThat(both.total()).isEqualTo(1);
    }

    @Test
    void titleSearchTreatsLikeWildcardsAsPlainCharacters() {
        newDiagram("Sale 50% off", DiagramType.ERD, false, T0);
        newDiagram("Sale 50 items", DiagramType.ERD, false, T0.plusSeconds(1));
        newDiagram("snake_case", DiagramType.ERD, false, T0.plusSeconds(2));
        newDiagram("snakeXcase", DiagramType.ERD, false, T0.plusSeconds(3));
        newDiagram("back\\slash", DiagramType.ERD, false, T0.plusSeconds(4));

        assertThat(titles(diagramRepository.findByOwner(owner, null, "50%", 0, 10))).containsExactly("Sale 50% off");
        assertThat(titles(diagramRepository.findByOwner(owner, null, "e_c", 0, 10))).containsExactly("snake_case");
        assertThat(titles(diagramRepository.findByOwner(owner, null, "k\\s", 0, 10))).containsExactly("back\\slash");
    }

    @Test
    void pageBeyondTheEndIsEmptyButStillReportsTheTotal() {
        newDiagram("a", DiagramType.ERD, false, T0);
        newDiagram("b", DiagramType.ERD, false, T0);

        DiagramPage beyond = diagramRepository.findByOwner(owner, null, "", 5, 10);

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(2);
        DiagramPage none = diagramRepository.findByOwner(owner, null, "no such title", 0, 10);
        assertThat(none.items()).isEmpty();
        assertThat(none.total()).isZero();
    }

    @Test
    void publicListContainsOnlyPublicDiagramsNewestFirst() {
        long before = diagramRepository.findPublic(0, 1).total();
        Instant future = Instant.parse("2100-01-01T00:00:00Z");   // mới hơn mọi sơ đồ khác trong CSDL test
        Diagram older = newDiagram("older public", DiagramType.ERD, true, future);
        Diagram newer = newDiagram("newer public", DiagramType.UML, true, future.plusSeconds(1));
        newDiagram("private", DiagramType.ERD, false, future.plusSeconds(2));

        DiagramPage page = diagramRepository.findPublic(0, 2);

        assertThat(page.total()).isEqualTo(before + 2);
        assertThat(page.items()).extracting(DiagramSummary::id).containsExactly(newer.getId(), older.getId());
        assertThat(page.items()).allMatch(DiagramSummary::isPublic);
    }

    // ---------- phiên bản: chép data ngay trong CSDL ----------

    @Test
    void snapshotsCopyTheCurrentDataWithIncreasingNumbers() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);

        DiagramVersion first = versionRepository.snapshot(UUID.randomUUID(), diagram.getId(), "first", owner, T0);
        diagramRepository.updateIfVersion(new Diagram(diagram.getId(), "Shop ERD", DiagramType.ERD, NEW_DATA, 1,
                false, owner, owner, null, T0.plusSeconds(1)), true).orElseThrow();
        DiagramVersionSummary second = versionRepository.snapshotSummary(UUID.randomUUID(), diagram.getId(), null,
                null, T0.plusSeconds(2));

        assertThat(first.versionNumber()).isEqualTo(1);
        assertSameJson(first.data(), DATA);
        assertThat(first.note()).isEqualTo("first");
        assertThat(first.createdBy()).isEqualTo(owner);
        assertThat(second.versionNumber()).isEqualTo(2);
        assertThat(second.createdBy()).isNull();
        assertThat(versionRepository.findByIdAndDiagramId(second.id(), diagram.getId())).get()
                .satisfies(v -> assertSameJson(v.data(), NEW_DATA));
        assertThat(versionRepository.findSummaries(diagram.getId()))
                .extracting(DiagramVersionSummary::versionNumber).containsExactly(2, 1);
        assertThat(versionRepository.findSummary(first.id(), diagram.getId())).contains(
                new DiagramVersionSummary(first.id(), 1, "first", owner, T0));
    }

    @Test
    void snapshotOfMissingDiagramIsNotFound() {
        assertThatThrownBy(() -> versionRepository.snapshot(UUID.randomUUID(), UUID.randomUUID(), null, owner, T0))
                .isInstanceOf(DiagramNotFoundException.class);
    }

    @Test
    void versionIsNotFoundThroughAnotherDiagram() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        DiagramVersion v = versionRepository.snapshot(UUID.randomUUID(), diagram.getId(), null, owner, T0);
        UUID other = newDiagram("Other", DiagramType.ERD, false, T0).getId();

        assertThat(versionRepository.findByIdAndDiagramId(v.id(), other)).isEmpty();
        assertThat(versionRepository.findSummary(v.id(), other)).isEmpty();
        assertThat(versionRepository.findSummaries(other)).isEmpty();
    }

    @Test
    void restoreCopiesTheSnapshotIntoTheDiagramAndBumpsItsVersion() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        DiagramVersion old = versionRepository.snapshot(UUID.randomUUID(), diagram.getId(), null, owner, T0);
        diagramRepository.updateIfVersion(new Diagram(diagram.getId(), "Shop ERD", DiagramType.ERD, NEW_DATA, 1,
                false, owner, owner, null, T0.plusSeconds(1)), true).orElseThrow();
        Instant now = T0.plusSeconds(5);

        Diagram restored = diagramRepository.restoreContent(diagram.getId(), old.id(), null, now).orElseThrow();

        assertSameJson(restored.getData(), DATA);
        assertThat(restored.getVersion()).isEqualTo(3);
        assertThat(restored.getUpdatedBy()).isNull();
        assertThat(restored.getUpdatedAt()).isEqualTo(now);
        assertThat(restored.getTitle()).isEqualTo("Shop ERD");
        assertThat(restored.getOwnerId()).isEqualTo(owner);
        assertThat(diagramRepository.findById(diagram.getId())).get()
                .satisfies(d -> assertSameJson(d.getData(), DATA));
        // snapshot là bất biến: khôi phục không đổi dòng phiên bản
        assertThat(versionRepository.findByIdAndDiagramId(old.id(), diagram.getId())).get().satisfies(v -> {
            assertThat(v.versionNumber()).isEqualTo(old.versionNumber());
            assertThat(v.data()).isEqualTo(old.data());
        });
    }

    @Test
    void restoringAVersionOfAnotherDiagramChangesNothing() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        Diagram other = newDiagram("Other", DiagramType.ERD, false, T0);
        DiagramVersion foreign = versionRepository.snapshot(UUID.randomUUID(), other.getId(), null, owner, T0);

        assertThat(diagramRepository.restoreContent(diagram.getId(), foreign.id(), owner, T0)).isEmpty();
        assertThat(diagramRepository.findAccessInfo(diagram.getId())).get()
                .extracting(DiagramAccessInfo::version).isEqualTo(1);
    }

    @Test
    void deletingADiagramCascadesToItsVersionsAndShares() {
        Diagram diagram = newDiagram("Shop ERD", DiagramType.ERD, false, T0);
        DiagramVersion v = versionRepository.snapshot(UUID.randomUUID(), diagram.getId(), null, owner, T0);
        DiagramShare share = newShare(diagram.getId());

        diagramRepository.deleteById(diagram.getId());

        assertThat(diagramRepository.findById(diagram.getId())).isEmpty();
        assertThat(versionRepository.findByIdAndDiagramId(v.id(), diagram.getId())).isEmpty();
        assertThat(shareRepository.findByShareToken(share.getShareToken())).isEmpty();
    }

    // ---------- link chia sẻ ----------

    @Test
    void insertedShareIsFoundByTokenAndByDiagram() {
        UUID diagramId = newDiagram("Shop ERD", DiagramType.ERD, false, T0).getId();
        DiagramShare share = newShare(diagramId);

        assertThat(shareRepository.findByShareToken(share.getShareToken())).get()
                .usingRecursiveComparison().isEqualTo(share);
        assertThat(shareRepository.findByDiagramId(diagramId)).singleElement()
                .usingRecursiveComparison().isEqualTo(share);
    }

    @Test
    void duplicateTokenIsReportedSoTheServiceCanRetry() {
        UUID diagramId = newDiagram("Shop ERD", DiagramType.ERD, false, T0).getId();
        DiagramShare share = newShare(diagramId);

        assertThatThrownBy(() -> shareRepository.insert(new DiagramShare(UUID.randomUUID(), diagramId,
                share.getShareToken(), ShareRole.EDIT, T0)))
                .isInstanceOf(ShareTokenTakenException.class);
    }

    @Test
    void updateRoleReturnsTheChangedShare() {
        DiagramShare share = newShare(newDiagram("Shop ERD", DiagramType.ERD, false, T0).getId());

        assertThat(shareRepository.updateRole(share.getId(), share.getDiagramId(), ShareRole.EDIT)).get()
                .usingRecursiveComparison().isEqualTo(new DiagramShare(share.getId(), share.getDiagramId(),
                        share.getShareToken(), ShareRole.EDIT, share.getCreatedAt()));
        assertThat(shareRepository.findByShareToken(share.getShareToken())).get()
                .extracting(DiagramShare::getRole).isEqualTo(ShareRole.EDIT);
    }

    @Test
    void roleUpdateAfterRevokeDoesNotRecreateTheLink() {
        DiagramShare share = newShare(newDiagram("Shop ERD", DiagramType.ERD, false, T0).getId());
        assertThat(shareRepository.deleteByIdAndDiagramId(share.getId(), share.getDiagramId())).get()
                .extracting(DiagramShare::getShareToken).isEqualTo(share.getShareToken());

        assertThat(shareRepository.updateRole(share.getId(), share.getDiagramId(), ShareRole.EDIT)).isEmpty();
        assertThat(shareRepository.findByShareToken(share.getShareToken())).isEmpty();
        assertThat(shareRepository.deleteByIdAndDiagramId(share.getId(), share.getDiagramId())).isEmpty();
    }

    @Test
    void shareCannotBeChangedOrRevokedThroughAnotherDiagram() {
        DiagramShare share = newShare(newDiagram("Shop ERD", DiagramType.ERD, false, T0).getId());
        UUID other = newDiagram("Other", DiagramType.ERD, false, T0).getId();

        assertThat(shareRepository.updateRole(share.getId(), other, ShareRole.EDIT)).isEmpty();
        assertThat(shareRepository.deleteByIdAndDiagramId(share.getId(), other)).isEmpty();
        assertThat(shareRepository.findByShareToken(share.getShareToken())).get()
                .extracting(DiagramShare::getRole).isEqualTo(ShareRole.VIEW);
    }

    private DiagramShare newShare(UUID diagramId) {
        return shareRepository.insert(new DiagramShare(UUID.randomUUID(), diagramId, "token-" + UUID.randomUUID(),
                ShareRole.VIEW, T0.truncatedTo(ChronoUnit.MICROS)));
    }

    private Diagram newDiagram(String title, DiagramType type, boolean isPublic, Instant updatedAt) {
        return diagramRepository.insert(new Diagram(null, title, type, DATA, 1, isPublic, owner, owner,
                T0.minusSeconds(3600), updatedAt));
    }

    private static void assertSameJson(DiagramData actual, DiagramData expected) {
        try {
            assertThat(JSON.readTree(actual.json())).isEqualTo(JSON.readTree(expected.json()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> titles(DiagramPage page) {
        return page.items().stream().map(DiagramSummary::title).toList();
    }
}
