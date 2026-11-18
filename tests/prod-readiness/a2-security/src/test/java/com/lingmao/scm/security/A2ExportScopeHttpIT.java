package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit exports must not silently omit inaccessible IDs or ignore the expected page company. */
class A2ExportScopeHttpIT extends A2BusinessFixture {
    private record Selection(String path, long mine, long foreign, long otherCompany) { }

    @Test void allFiveDocumentExportsRejectMixedScopeAndWrongCompanyBeforeReturningFileHeaders() throws Exception {
        Fixture f = fixture(); Account foreign = role("admin", f.company());
        String[] rights = {"order:order:export", "purchase:order:export", "production:order:export", "inventory:doc:export", "inventory:count:export"};
        Account self = custom(f.company(), "SELF", rights), all = custom(f.company(), "ALL", rights);
        List<long[]> documents = new ArrayList<>();
        for (Account owner : List.of(self, foreign, self)) {
            long order = sourceOrder(f, owner, "APPROVED", f.company());
            documents.add(new long[] {order, sourcePurchase(f, owner), sourceProduction(f, owner, order, "DRAFT"),
                    inventory(f, owner), count(f, owner)});
        }
        String[] tables = {"ord_order", "pur_order", "prd_order", "inv_doc", "inv_count"};
        String[] paths = {"/api/orders/export?", "/api/purchase-orders/export?", "/api/production-orders/export?kind=OUTSOURCE&",
                "/api/inventory-docs/export?kind=IN&", "/api/inventory-counts/export?"};
        for (int i = 0; i < paths.length; i++) {
            jdbc.update("UPDATE " + tables[i] + " SET company_id=? WHERE id=?", f.otherCompany(), documents.get(2)[i]);
            var selection = new Selection(paths[i], documents.get(0)[i], documents.get(1)[i], documents.get(2)[i]);
            var mixed = raw(self, "GET", selection.path() + "ids=" + selection.mine() + "," + selection.foreign(), null, null, null);
            assertThat(node(mixed).path("code").intValue()).as(selection.path()).isEqualTo(404);
            assertThat(mixed.headers().firstValue("Content-Disposition")).isEmpty();
            var company = raw(all, "GET", selection.path() + "ids=" + selection.mine() + "," + selection.otherCompany(), null, f.company(), null);
            assertThat(node(company).path("code").intValue()).as(selection.path()).isEqualTo(403);
            assertThat(company.headers().firstValue("Content-Disposition")).isEmpty();
            var valid = raw(self, "GET", selection.path() + "ids=" + selection.mine(), null, f.company(), null);
            assertThat(valid.statusCode()).as(selection.path()).isEqualTo(200);
            assertThat(valid.headers().firstValue("Content-Type").orElse("")).contains("spreadsheet");
            assertThat(A2BusinessHttpIT.headers(valid.body(), 0)).isNotEmpty();
        }
    }

    private long inventory(Fixture f, Account owner) {
        long id = jdbc.queryForObject("""
                INSERT INTO inv_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,create_by)
                VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,?) RETURNING id
                """, Long.class, "A2E" + suffix(), f.company(), f.warehouse(), owner.id());
        jdbc.update("INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty,price,amount) VALUES(?,1,?,1,13.37,13.37)", id, f.materialSku());
        return id;
    }
    private long count(Fixture f, Account owner) {
        return jdbc.queryForObject("""
                INSERT INTO inv_count(no,company_id,warehouse_id,purpose,scope,count_date,counter_id,create_by,status)
                VALUES(?,?,?,'OPENING','PICKED',CURRENT_DATE,?,?,'VOIDED') RETURNING id
                """, Long.class, "A2C" + suffix(), f.company(), f.warehouse(), owner.id(), owner.id());
    }
}
