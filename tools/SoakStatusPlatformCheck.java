import java.sql.*;
import java.util.*;

/** Read-only status check for synthetic soak records; never prints source text. */
public final class SoakStatusPlatformCheck {
    public static void main(String[] ids) throws Exception {
        boolean ledger=ids.length==2&&ids[0].equals("--budget-ledger");
        boolean budget=ids.length==2&&(ids[0].equals("--budget-jobs")||ledger);
        if(budget)ids=new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.nio.file.Path.of(ids[1]).toFile(),String[].class);
        if(ids.length==0||ids.length>100)throw new IllegalArgumentException("bounded synthetic IDs required");
        for(String id:ids)UUID.fromString(id);
        Class.forName("dm.jdbc.driver.DmDriver");
        try (var c = DriverManager.getConnection(System.getenv("AUDIT_DB_URL"), System.getenv("AUDIT_DB_USERNAME"), System.getenv("AUDIT_DB_PASSWORD"))) {
            var placeholders=String.join(",", Collections.nCopies(ids.length, "?"));
            var sql = budget?"SELECT J.ID,J.STATUS,T.STATUS FROM BUDGET_MATERIAL_JOB J JOIN BUDGET_REVIEW_TASK T ON T.ID=J.ID WHERE J.ID IN ("+placeholders+")":
                "SELECT ID,STATUS,ERROR_CODE FROM BUSINESS_MATERIAL_INTAKE WHERE ID IN ("+placeholders+")";
            try (var p = c.prepareStatement(sql)) {
                for (int i = 0; i < ids.length; i++) p.setString(i + 1, ids[i]);
                try (var r = p.executeQuery()) {
                    while (r.next()) System.out.println("id=" + r.getString(1) + " status=" + r.getString(2) + (budget?" taskStatus=":" errorCode=") + Objects.toString(r.getString(3), ""));
                }
            }
            if(ledger)try(var p=c.prepareStatement("SELECT STATUS,UNIT_COUNT,NEXT_UNIT,CALL_COUNT,USED_MILLIS,REASON_CODE FROM MATERIAL_EXTRACTION_LEDGER WHERE ID=?")) {
                for(String id:ids) {
                    p.setString(1,UUID.nameUUIDFromBytes(("material-extraction/BUDGET/"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                    try(var r=p.executeQuery()) {
                        if(r.next())System.out.printf("id=%s ledgerStatus=%s units=%d acknowledged=%d calls=%d usedMillis=%d reason=%s%n",id,r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getLong(5),Objects.toString(r.getString(6),""));
                    }
                }
            }
        }
    }
}
