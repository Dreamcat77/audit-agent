// Status tests never export spreadsheets; the application's pinned XLSX package
// is unavailable in the isolated test environment. Fail loudly if used here.
function outsideFixture(): never { throw new Error('Spreadsheet export is outside this status fixture'); }
export const utils={book_new:outsideFixture,book_append_sheet:outsideFixture,json_to_sheet:outsideFixture};
export const writeFile=outsideFixture;
