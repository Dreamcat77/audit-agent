package com.fuquan.audit.procurement.autocase;

import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import java.util.*;
import java.math.BigDecimal;

/** A model row key alone cannot pair prices and companies. Require one source line per offer. */
final class CaseMultiOffers {
    private CaseMultiOffers() {}
    static List<Offer> read(Document document) {
        if(Set.of("AWARD","EVALUATION_REPORT").contains(ProcurementMaterialSemantics.role(document)))return List.of();
        var suppliers=CaseFactPolicy.facts(document,"supplierName");var amounts=CaseFactPolicy.totals(document,"quoteAmount");
        if(suppliers.stream().map(Fact::value).distinct().count()<2||amounts.isEmpty())return List.of();
        var offers=new ArrayList<Offer>();
        for(var segment:document.segments()) {
            var localSuppliers=suppliers.stream().filter(f->f.locator().equals(segment.locator())).toList();
            var localAmounts=amounts.stream().filter(f->f.locator().equals(segment.locator())).toList();
            if(localSuppliers.isEmpty()||localAmounts.isEmpty())continue;
            int start=0;var breaks=java.util.regex.Pattern.compile("\\R").matcher(segment.text());
            while(start<=segment.text().length()) {
                boolean more=breaks.find();int end=more?breaks.start():segment.text().length();
                String line=segment.text().substring(start,end);
                var names=localSuppliers.stream().map(Fact::value).filter(line::contains).distinct().toList();
                var prices=new TreeSet<BigDecimal>();
                for(var f:localAmounts)if(line.contains(f.value()))ProcurementMaterialSemantics.money(document,f).ifPresent(prices::add);
                if(names.size()==1&&prices.size()==1&&prices.first().signum()>0) {
                    String sourceRow=segment.locator()+"/quote-line:"+start+":"+end;
                    offers.add(new Offer(names.get(0),prices.first(),document.id(),List.of(),"DATA_INSUFFICIENT",sourceRow));
                }
                if(!more)break;start=breaks.end();
            }
        }
        return offers.size()>=2?List.copyOf(offers):List.of();
    }
    static List<SourceEvidence> quoteEvidence(CaseReviewContext.Context context,Document document,Offer offer) {
        if(offer.sourceRow()==null)return CaseFactPolicy.facts(document,"quoteAmount").stream()
                .flatMap(f->CaseReviewContext.factEvidence(context,document,f).stream()).distinct().toList();
        var match=java.util.regex.Pattern.compile("/quote-line:([0-9]+):([0-9]+)$").matcher(offer.sourceRow());
        if(!match.find())return List.of();
        String locator=offer.sourceRow().substring(0,match.start());int start=Integer.parseInt(match.group(1)),end=Integer.parseInt(match.group(2));
        var segment=document.segments().stream().filter(s->s.locator().equals(locator)&&end<=s.text().length()).findFirst();
        if(segment.isEmpty())return List.of();String line=segment.get().text().substring(start,end);
        return context.evidence().stream().filter(e->e.documentId().equals(document.id())&&e.locator().equals(locator)&&e.excerpt().contains(line)).toList();
    }
}
