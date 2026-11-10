import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),JSZip=require('jszip'),XLSX=require('xlsx');
export async function wordFixture({duplicate=false,continuous=false}={}){
 const zip=new JSZip();
 zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');
 zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
 const paragraph=text=>`<w:p><w:r><w:t>${text}</w:t></w:r></w:p>`;
 const quote='<w:p><w:r><w:t>甲方应于验收合格后</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>十日内支付尾款。</w:t></w:r></w:p>';
 const breakPage=continuous?'':'<w:p><w:r><w:br w:type="page"/></w:r></w:p>';
 let pages=[];
 for(let i=1;i<=12;i++)pages.push(paragraph(`第${i}页原文件标题`)+Array.from({length:continuous?25:3},(_,j)=>paragraph(`前后文${i}条${j}：原件中的验收、服务和付款约定。`)).join('')+
  (i===2||i===9||duplicate?quote:'')+'<w:tbl><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="6"/></w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid><w:tr><w:tc>'+paragraph('原件表格：服务范围')+'</w:tc><w:tc>'+paragraph('平台运维')+'</w:tc></w:tr></w:tbl>');
 // Normally a single citation appears once. Page 9 has another independent clause.
 if(!duplicate)pages[8]=pages[8].replace(quote,paragraph('乙方应按服务标准提交验收资料。'));
 zip.file('word/document.xml',`<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${pages.join(breakPage)}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:bottom="1440" w:left="1440" w:right="1440"/></w:sectPr></w:body></w:document>`);
 return zip.generateAsync({type:'nodebuffer'});
}
export function sheetFixture(legacy=false){
 const book=XLSX.utils.book_new();
 for(const name of ['预算批复','其他项目']){
  const sheet=XLSX.utils.aoa_to_sheet([['原始预算批复表'],['项目名称','政务信息平台'],['项目预算',2200000],['申报金额',2300000],...Array.from({length:60},(_,i)=>[`原始明细第${i+1}行`,1000+i])]);
  sheet.B3.z='#,##0.00';sheet['!merges']=[{s:{r:0,c:0},e:{r:0,c:1}}];sheet['!cols']=[{wch:30},{wch:24}];XLSX.utils.book_append_sheet(book,sheet,name);
 }
 return XLSX.write(book,{type:'buffer',bookType:legacy?'biff8':'xlsx'});
}
