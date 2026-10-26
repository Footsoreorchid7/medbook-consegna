package it.medbook.referto;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfValidatorTest {
    @TempDir Path temp;
    @BeforeEach void canonicalTemp() throws IOException { temp = temp.toRealPath(); }
    QpdfValidator fake() throws IOException {
        return new QpdfValidator(Path.of(System.getProperty("java.home"),"bin/java"),Duration.ofSeconds(1)) {
            @Override public void validate(Path file) { }
        };
    }
    Path pdf(int pages, String forbidden) throws Exception {
        Path file=Files.createTempFile(temp,"pdf-",".pdf");
        try(PDDocument doc=new PDDocument()) {
            for(int i=0;i<pages;i++) { PDPage page=new PDPage();page.setResources(new PDResources());doc.addPage(page); }
            if(forbidden!=null) doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName(forbidden),new COSDictionary());
            doc.save(file.toFile());
        }
        return file;
    }
    PdfValidator validator() throws IOException { return new PdfValidator(fake(),temp); }
    @Test void validOriginalUnchanged() throws Exception {
        Path file=pdf(1,null); byte[] before=Files.readAllBytes(file); validator().validate(file); assertArrayEquals(before,Files.readAllBytes(file));
    }
    @ParameterizedTest @ValueSource(strings={"OpenAction","AA","JS","JavaScript","EmbeddedFiles","EF","AF","RichMedia","RichMediaContent","RichMediaSettings","XFA","3D","Movie","Sound"})
    void forbiddenStructures(String key) throws Exception { assertThrows(PdfRifiutatoException.class,()->validator().validate(pdf(1,key))); }
    @ParameterizedTest @ValueSource(strings={"JavaScript","Launch","URI","GoToR","GoToE","SubmitForm","ImportData","Rendition","RichMediaExecute"})
    void indirectAndCyclicActionsRejected(String action) throws Exception {
        COSDictionary root=new COSDictionary(),active=new COSDictionary();active.setName(COSName.S,action);
        active.setItem(COSName.getPDFName("Next"),root);root.setItem(COSName.getPDFName("A"),new COSObject(active));
        assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(root));
    }
    @Test void benignCyclesTerminateAndActiveNodesRemainVisible() throws Exception {
        COSDictionary root=new COSDictionary(); root.setItem(COSName.getPDFName("cycle"),new COSObject(root)); PdfValidator.inspect(root);
        COSDictionary active=new COSDictionary();active.setName(COSName.S,"JavaScript"); root.setItem(COSName.getPDFName("hidden"),active);
        assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(root));
    }
    @Test void encryptedIncludingEmptyPasswordRejected() throws Exception {
        for(String password:new String[]{"","synthetic"}) {
            Path file=Files.createTempFile(temp,"encrypted-",".pdf");
            try(PDDocument doc=new PDDocument()){{ PDPage page=new PDPage();page.setResources(new PDResources());doc.addPage(page); }StandardProtectionPolicy policy=new StandardProtectionPolicy("owner",password,new AccessPermission());policy.setEncryptionKeyLength(128);doc.protect(policy);doc.save(file.toFile());}
            assertThrows(PdfRifiutatoException.class,()->validator().validate(file));
        }
    }
    @Test void pageLimitsAndCounts() throws Exception {
        validator().validate(pdf(200,null)); assertThrows(PdfRifiutatoException.class,()->validator().validate(pdf(201,null)));
        assertThrows(PdfRifiutatoException.class,()->validator().validate(pdf(0,null)));
        Path file=Files.createTempFile(temp,"count-",".pdf");
        try(PDDocument doc=new PDDocument()){{ PDPage page=new PDPage();page.setResources(new PDResources());doc.addPage(page); }doc.getPages().getCOSObject().setInt(COSName.COUNT,9);doc.save(file.toFile());}
        assertThrows(PdfRifiutatoException.class,()->validator().validate(file));
    }
    @Test void malformedAndTruncatedRejected() throws Exception {
        Path file=Files.writeString(temp.resolve("invalid.pdf"),"not a PDF"); assertThrows(PdfRifiutatoException.class,()->validator().validate(file));
        Path good=pdf(1,null); byte[] bytes=Files.readAllBytes(good); Files.write(file,java.util.Arrays.copyOf(bytes,bytes.length/2));
        assertThrows(PdfRifiutatoException.class,()->validator().validate(file));
    }
    @Test void depthAndObjectLimits() throws Exception {
        COSDictionary root=new COSDictionary(),cursor=root;
        for(int i=0;i<65;i++){COSDictionary child=new COSDictionary();cursor.setItem(COSName.getPDFName("child"),child);cursor=child;}
        assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(root));
        COSArray many=new COSArray();for(int i=0;i<50001;i++)many.add(new COSString("test"));
        assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(many));
    }
    @Test void cacheQuotaPrivatePermissionsAndCleanup() throws Exception {
        try(PrivateStreamCache cache=new PrivateStreamCache(temp)) {
            var a=cache.createBuffer(); var b=cache.createBuffer(); a.seek(PrivateStreamCache.LIMIT-1);a.write(1);
            assertThrows(PdfRifiutatoException.class,()->b.write(1)); a.clear(); b.write(1);
            try(var paths=Files.list(temp)){for(Path file:paths.toList())assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));}
        }
        try(var paths=Files.list(temp)){assertEquals(0,paths.count());}
    }
    @Test void atMostTwoWholePipelineValidations() throws Exception {
        CountDownLatch two = new CountDownLatch(2), thirdAttempt = new CountDownLatch(1), thirdEntered = new CountDownLatch(1);
        Semaphore release = new Semaphore(0);
        Path firstFile = pdf(1, null), secondFile = pdf(1, null), thirdFile = pdf(1, null);
        var qpdf = new QpdfValidator(Path.of(System.getProperty("java.home"), "bin/java"), Duration.ofSeconds(1)) {
            @Override public void validate(Path file) throws IOException {
                if (file.equals(thirdFile)) { thirdEntered.countDown(); return; }
                two.countDown();
                try { if (!release.tryAcquire(5, TimeUnit.SECONDS)) { throw new IOException("controlled timeout"); } }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        };
        var validator = new PdfValidator(qpdf, temp);
        var field = PdfValidator.class.getDeclaredField("permits"); field.setAccessible(true);
        Semaphore permits = (Semaphore) field.get(validator);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> { validator.validate(firstFile); return null; });
            var second = executor.submit(() -> { validator.validate(secondFile); return null; });
            assertTrue(two.await(5, TimeUnit.SECONDS));
            var third = executor.submit(() -> { thirdAttempt.countDown(); validator.validate(thirdFile); return null; });
            assertTrue(thirdAttempt.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!permits.hasQueuedThreads() && thirdEntered.getCount() != 0 && System.nanoTime() < deadline) { Thread.onSpinWait(); }
            assertTrue(permits.hasQueuedThreads(), "Third validation must be waiting for a permit");
            assertEquals(1, thirdEntered.getCount()); assertEquals(0, permits.availablePermits());
            release.release();
            assertTrue(thirdEntered.await(5, TimeUnit.SECONDS));
            third.get(5, TimeUnit.SECONDS);
            release.release(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { release.release(2); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void realQpdfAndPdfboxPipeline() throws Exception {
        String path=System.getenv("MEDBOOK_QPDF_EXECUTABLE");assumeTrue(path!=null,"qpdf isolato richiesto.");
        var qpdf=new QpdfValidator(Path.of(path),Duration.ofSeconds(10));qpdf.verifyVersion();
        var validator=new PdfValidator(qpdf,temp);validator.validate(pdf(1,null));
        assertThrows(PdfRifiutatoException.class,()->validator.validate(pdf(1,"OpenAction")));
        Path malformed=Files.writeString(temp.resolve("malformed.pdf"),"%PDF-1.7\n1 0 obj << /Type /Catalog INVALID >> endobj\n%%EOF");
        assertThrows(PdfRifiutatoException.class,()->validator.validate(malformed));
    }
    @Test void missingFileIsTechnicalRatherThanDocumentRejection()throws Exception {
        IOException error=assertThrows(IOException.class,()->validator().validate(temp.resolve("missing.pdf")));
        assertFalse(error instanceof PdfRifiutatoException);
    }
    @Test void scratchUnavailableIsTechnical()throws Exception {
        try(PrivateStreamCache cache=new PrivateStreamCache(temp.resolve("missing"))) {
            IOException error=assertThrows(IOException.class,cache::createBuffer);
            assertFalse(error instanceof PdfRifiutatoException);
        }
    }

    Path raw(String catalog,int count,String extra)throws IOException {
        java.util.List<String> objects=new java.util.ArrayList<>(java.util.List.of(catalog,"<< /Type /Pages /Kids [3 0 R] /Count "+count+" >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> >>"));
        if(extra!=null)objects.add(extra);
        return rawObjects(objects);
    }
    Path rawObjects(java.util.List<String> objects)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write("%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));java.util.List<Integer> offsets=new java.util.ArrayList<>();
        for(int i=0;i<objects.size();i++){offsets.add(out.size());out.write(((i+1)+" 0 obj\n"+objects.get(i)+"\nendobj\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        int xref=out.size();out.write(("xref\n0 "+(objects.size()+1)+"\n0000000000 65535 f \n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for(int offset:offsets)out.write(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offset).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(("trailer\n<< /Root 1 0 R /Size "+(objects.size()+1)+" >>\nstartxref\n"+xref+"\n%%EOF\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return Files.write(Files.createTempFile(temp,"raw-",".pdf"),out.toByteArray());
    }
    QpdfValidator realQpdf()throws IOException {
        String executable=System.getenv("MEDBOOK_QPDF_EXECUTABLE");assumeTrue(executable!=null,"qpdf isolato richiesto.");return new QpdfValidator(Path.of(executable),Duration.ofSeconds(10));
    }
    @ParameterizedTest @ValueSource(strings={
        "<< /Type /Catalog /Pages 2 0 R INVALID_TOKEN >>",
        "<< /Type /Catalog /Pages 2 0 R /OpenAction << /S /JavaScript /JS (test) >> /OpenAction [3 0 R /Fit] >>",
        "<< /Type /Catalog /Pages 2 0 R /OpenAction [3 0 R /Fit] /OpenAction << /S /JavaScript /JS (test) >> >>",
        "<< /Type /Catalog /Pages 2 0 R /Names << /JavaScript << /Names [(x) << /S /JavaScript /JS (test) >>] >> >> /Names << >> >>",
        "<< /Type /Catalog /Pages 2 0 R /Names << >> /Names << /JavaScript << /Names [(x) << /S /JavaScript /JS (test) >>] >> >> >>"
    })
    void qpdfRejectsMalformedCatalogAndAmbiguousDuplicateKeys(String catalog)throws Exception {
        Path file=raw(catalog,1,null);var qpdf=realQpdf();assertThrows(PdfRifiutatoException.class,()->qpdf.validate(file));
    }
    @Test void qpdfSuccessDoesNotReplaceSemanticChecks()throws Exception {
        Path count=raw("<< /Type /Catalog /Pages 2 0 R >>",9,null);var qpdf=realQpdf();qpdf.validate(count);
        assertThrows(PdfRifiutatoException.class,()->new PdfValidator(qpdf,temp).validate(count));
        Path orphan=raw("<< /Type /Catalog /Pages 2 0 R >>",1,"<< /S /JavaScript /JS (test) >>");qpdf.validate(orphan);
        assertThrows(PdfRifiutatoException.class,()->new PdfValidator(qpdf,temp).validate(orphan));
    }

    @ParameterizedTest @ValueSource(strings={"EmbeddedFile","Filespec","FileAttachment","RichMedia","3D"})
    void forbiddenTypesEvenWithoutConventionalContentKeys(String type)throws Exception {
        COSDictionary object=new COSDictionary();
        object.setName(type.equals("EmbeddedFile")||type.equals("Filespec")?COSName.TYPE:COSName.SUBTYPE,type);
        assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(object));
    }

    @Test void realEmbeddedAttachmentRejectedAfterSuccessfulQpdfCheck()throws Exception {
        Path file=Files.createTempFile(temp,"attachment-",".pdf");
        try(PDDocument doc=new PDDocument()) {
            PDPage page=new PDPage();page.setResources(new PDResources());doc.addPage(page);
            var specification=new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
            specification.setFile("synthetic.txt");
            var embedded=new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(doc,
                    new ByteArrayInputStream("synthetic fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            specification.setEmbeddedFile(embedded);
            var tree=new PDEmbeddedFilesNameTreeNode();tree.setNames(java.util.Map.of("synthetic.txt",specification));
            var names=new PDDocumentNameDictionary(doc.getDocumentCatalog());names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);doc.save(file.toFile());
        }
        var qpdf=realQpdf();qpdf.validate(file);
        assertThrows(PdfRifiutatoException.class,()->new PdfValidator(qpdf,temp).validate(file));
    }

    private COSArray chain(int depth, COSBase tail) {
        COSArray root = new COSArray(), cursor = root;
        for (int i = 1; i < depth; i++) { COSArray next = new COSArray(); cursor.add(next); cursor = next; }
        if (tail != null) cursor.add(tail);
        return root;
    }
    @Test void exactDirectDepthBoundary() throws Exception {
        PdfValidator.inspect(chain(64, COSNull.NULL));
        assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(chain(65, null)));
    }
    @Test void sharedLongestPathIndependentOfTraversalOrder() throws Exception {
        for (boolean reverse : new boolean[]{false, true}) {
            COSArray shared = chain(2, null), root = new COSArray();
            COSArray longer = chain(62, shared);
            if (reverse) { root.add(longer); root.add(shared); } else { root.add(shared); root.add(longer); }
            assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(root));
        }
    }
    @Test void sharedDagDoesNotExpandCombinatorially() throws Exception {
        COSArray root = new COSArray(), cursor = root;
        for (int i = 1; i < 64; i++) { COSArray next = new COSArray(); cursor.add(next); cursor.add(next); cursor = next; }
        assertTimeout(java.time.Duration.ofSeconds(3), () -> PdfValidator.inspect(root));
    }
    @Test void directCyclesRejectedIndirectCyclesInspected() throws Exception {
        COSArray direct = new COSArray(); direct.add(direct);
        assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(direct));
        COSArray indirect = new COSArray(); indirect.add(new COSObject(indirect));
        PdfValidator.inspect(indirect);
        COSDictionary active = new COSDictionary(); active.setName(COSName.S, "JavaScript"); indirect.add(new COSObject(active));
        assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(indirect));
    }
    @Test void indirectReferencesResetDepthWithoutSkippingSecurity() throws Exception {
        COSArray root = new COSArray(), cursor = root;
        for (int i = 0; i < 2000; i++) { COSArray next = new COSArray(); cursor.add(new COSObject(next)); cursor = next; }
        PdfValidator.inspect(root);
        cursor.add(COSName.getPDFName("ordinary"));
        COSDictionary active = new COSDictionary(); active.setItem(COSName.getPDFName("JS"), new COSString("synthetic")); cursor.add(active);
        assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(root));
    }
    @Test void exactDistinctObjectLimit() throws Exception {
        COSArray root = new COSArray(); for (int i = 1; i < 50000; i++) root.add(new COSString("synthetic"));
        PdfValidator.inspect(root); root.add(new COSString("synthetic"));
        assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(root));
    }
    @Test void exactLinkOccurrenceLimit() throws Exception {
        COSArray root = new COSArray(); COSArray shared = new COSArray();
        for (int i = 0; i < 1000000; i++) root.add(shared);
        PdfValidator.inspect(root); root.add(shared);
        assertThrows(PdfRifiutatoException.class, () -> PdfValidator.inspect(root));
    }
    @Test void unresolvedJavaNullIsTechnicalButCosNullIsAllowed() throws Exception {
        PdfValidator.inspect(new COSObject(COSNull.NULL));
        assertThrows(PdfTechnicalException.class, () -> PdfValidator.inspect(new COSObject(null)));
    }

    @Test void alternatingContainersStreamAndPrimitives() throws Exception {
        COSDictionary root = new COSDictionary(), cursor = root;
        for (int i=0;i<31;i++) { COSArray array=new COSArray(); COSDictionary child=new COSDictionary(); cursor.setItem(COSName.getPDFName("child"),array); array.add(COSInteger.ONE); array.add(child); cursor=child; }
        try(COSStream stream=new COSStream()) {
            cursor.setItem(COSName.getPDFName("stream"),stream); PdfValidator.inspect(root);
            stream.setItem(COSName.getPDFName("child"),new COSArray());
            assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(root));
        }
    }
    @Test void sharedActiveNodeIsInspected() throws Exception {
        COSArray root=new COSArray(); COSDictionary active=new COSDictionary(); active.setName(COSName.S,"JavaScript");
        root.add(new COSObject(active)); root.add(active);
        assertThrows(PdfRifiutatoException.class,()->PdfValidator.inspect(root));
    }
    @Test void seventyIndirectPageLevelsAreAccepted() throws Exception {
        java.util.List<String> objects=new java.util.ArrayList<>(); objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        for(int id=2;id<=71;id++) objects.add("<< /Type /Pages /Kids ["+(id+1)+" 0 R] /Count 1"+(id==2?"":" /Parent "+(id-1)+" 0 R")+" >>");
        objects.add("<< /Type /Page /Parent 71 0 R /MediaBox [0 0 100 100] /Resources << >> >>");
        validator().validate(rawObjects(objects));
    }
    @Test void pageParentMismatchAndPageCycleRejected() throws Exception {
        Path mismatch=rawObjects(java.util.List.of("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 1 0 R /MediaBox [0 0 100 100] >>"));
        assertThrows(PdfRifiutatoException.class,()->validator().validate(mismatch));
        COSDictionary root=new COSDictionary();root.setName(COSName.TYPE,"Pages");root.setInt(COSName.COUNT,1);COSArray kids=new COSArray();kids.add(new COSObject(root));root.setItem(COSName.KIDS,kids);
        var method=PdfValidator.class.getDeclaredMethod("pages",COSBase.class,COSDictionary.class,java.util.Set.class,int.class);method.setAccessible(true);
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,root,null,java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),0));
        assertInstanceOf(PdfRifiutatoException.class,error.getCause());
    }

    @Test void lazyDereferenceFailureHiddenByPdfboxIsTechnical()throws Exception {
        ICOSParser parser=new ICOSParser(){
            public COSBase dereferenceCOSObject(COSObject object)throws IOException{throw new IOException("synthetic read failure");}
            public org.apache.pdfbox.io.RandomAccessReadView createRandomAccessReadView(long start,long length)throws IOException{throw new IOException("unused");}
        };
        COSObject object=new COSObject(new COSObjectKey(999,0),parser);
        assertThrows(PdfTechnicalException.class,()->PdfValidator.inspect(object));
    }
    @Test void sharedMaximumValidDepthAcceptedInBothOrders()throws Exception {
        for(boolean reverse:new boolean[]{false,true}) {
            COSArray shared=chain(2,null),root=new COSArray(),longer=chain(61,shared);
            if(reverse){root.add(longer);root.add(shared);}else{root.add(shared);root.add(longer);}
            PdfValidator.inspect(root);
        }
    }

    private final class FaultFiles implements PrivateStreamCache.FileOperations {
        final String fault;
        final IOException original = new IOException("synthetic file failure");
        final IOException deletion = new IOException("synthetic deletion failure");
        int closeAttempts, deleteAttempts;
        FaultFiles(String fault) { this.fault = fault; }
        public RandomAccessFile open(Path path) throws IOException {
            if (fault.equals("open")) { throw original; }
            return new RandomAccessFile(path.toFile(), "rw") {
                private void failOn(String operation) throws IOException { if (fault.equals(operation)) { throw original; } }
                @Override public long getFilePointer() throws IOException { failOn("position"); return super.getFilePointer(); }
                @Override public void seek(long position) throws IOException { failOn("seek"); super.seek(position); }
                @Override public long length() throws IOException { failOn("length"); return super.length(); }
                @Override public void setLength(long length) throws IOException { failOn("clear"); super.setLength(length); }
                @Override public int read() throws IOException { failOn("read"); return super.read(); }
                @Override public void write(int value) throws IOException { failOn("write"); super.write(value); }
                @Override public void close() throws IOException {
                    closeAttempts++; super.close();
                    if (fault.equals("close") || fault.equals("both")) { throw original; }
                }
            };
        }
        public void delete(Path path) throws IOException {
            deleteAttempts++;
            if (fault.equals("delete") || fault.equals("both")) { throw deletion; }
            Files.deleteIfExists(path);
        }
    }
    private void cacheOperation(PrivateStreamCache cache, String operation) throws IOException {
        var buffer = cache.createBuffer();
        switch (operation) {
            case "position" -> buffer.getPosition();
            case "seek" -> buffer.seek(0);
            case "length" -> buffer.length();
            case "clear" -> buffer.clear();
            case "read" -> buffer.read();
            default -> buffer.write(1); // includes grow() -> getPosition()
        }
    }
    @ParameterizedTest @ValueSource(strings={"position", "seek", "length", "clear", "grow", "read", "write", "open"})
    void cacheIoFaultsKeepOriginalCauseAndReturnHttp500(String operation) throws Exception {
        var files = new FaultFiles(operation.equals("grow") ? "position" : operation);
        try (var cache = new PrivateStreamCache(temp, files)) {
            var error = assertThrows(PdfTechnicalException.class, () -> cacheOperation(cache, operation));
            assertSame(files.original, error.getCause());
        }
        var validator = new PdfValidator(fake(), temp) {
            @Override public void validate(Path original) throws IOException {
                try (var cache = new PrivateStreamCache(temp, files)) { cacheOperation(cache, operation); }
            }
        };
        assertUploadStatus(validator, 500);
    }
    private void assertUploadStatus(PdfValidator validator, int expected) throws Exception {
        var storage = new RefertoStorage(temp.resolve("private"));
        var repository = new it.medbook.repository.RefertoRepository(null, null) {
            @Override public void verifyCompetence(int booking, int doctor) { }
        };
        var service = new it.medbook.service.RefertoService(repository, storage, validator);
        var exchange = new it.medbook.support.AuthenticatedTestExchange("POST", "/api/prenotazioni/197/referto", "binary", 7);
        exchange.getRequestHeaders().set("Content-Type", "application/pdf");
        new it.medbook.controller.RefertoController(service, new com.fasterxml.jackson.databind.ObjectMapper()).handle(exchange);
        assertEquals(expected, exchange.exchange.status); assertTrue(exchange.exchange.closed);
        assertFalse(exchange.exchange.body().contains("synthetic"));
    }
    @Test void cacheQuotaStillReturnsHttp400() throws Exception {
        var validator = new PdfValidator(fake(), temp) {
            @Override public void validate(Path original) throws IOException {
                try (var cache = new PrivateStreamCache(temp)) {
                    var buffer = cache.createBuffer(); buffer.seek(PrivateStreamCache.LIMIT); buffer.write(1);
                }
            }
        };
        assertUploadStatus(validator, 400);
    }
    @ParameterizedTest @ValueSource(strings={"close", "delete", "both"})
    void cacheCleanupAttemptsBothOperationsAndPreservesFirstFailure(String fault) throws Exception {
        var files = new FaultFiles(fault); var cache = new PrivateStreamCache(temp, files);
        var buffer = cache.createBuffer();
        var error = assertThrows(PdfTechnicalException.class, buffer::close);
        assertSame(fault.equals("delete") ? files.deletion : files.original, error.getCause());
        if (fault.equals("both")) { assertArrayEquals(new Throwable[]{files.deletion}, error.getCause().getSuppressed()); }
        assertEquals(1, files.closeAttempts); assertEquals(1, files.deleteAttempts);
        buffer.close(); cache.close(); assertEquals(1, files.closeAttempts); assertEquals(1, files.deleteAttempts);
    }
    @Test void parsingFailureRemainsPrimaryWhenCacheCleanupFails() throws Exception {
        var files = new FaultFiles("both");
        Path malformed = Files.writeString(temp.resolve("bad.pdf"), "not a PDF");
        var error = assertThrows(PdfRifiutatoException.class, () -> {
            try (var cache = new PrivateStreamCache(temp, files)) {
                cache.createBuffer();
                validator().validate(malformed); // actual PDFBox parsing rejection
            }
        });
        assertEquals(1, error.getSuppressed().length);
        var cleanup = assertInstanceOf(PdfTechnicalException.class, error.getSuppressed()[0]);
        assertInstanceOf(PdfTechnicalException.class, cleanup.getCause());
        assertSame(files.original, cleanup.getCause().getCause());
        assertSame(files.deletion, files.original.getSuppressed()[0]);
        assertEquals(1, files.closeAttempts); assertEquals(1, files.deleteAttempts);
    }

}
