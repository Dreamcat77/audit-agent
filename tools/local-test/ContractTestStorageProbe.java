import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Uses create-only actual NTFS hard links; never touches historical storage. */
public class ContractTestStorageProbe {
  static final Path REPO=Path.of("D:/code/audit_agent_1D/.worktrees/integration-pre-main-20261010").toAbsolutePath().normalize();
  public static void main(String[] args){try{if(args.length!=1)throw new IllegalStateException("BAD_STORAGE_ARGUMENTS");Path root=Path.of(args[0]).toAbsolutePath().normalize();if(!root.equals(REPO.resolve(".runtime/contract-repair-test/storage")))throw new IllegalStateException("FRESH_STORAGE_OUTSIDE_APPROVED_ROOT");fresh(root);System.out.println("FRESH_TEST_STORAGE=PASS HARDLINK_CREATE_ONLY_AND_SHA=PASS");}catch(Exception e){System.out.println("FRESH_TEST_STORAGE=BLOCKED type="+e.getClass().getSimpleName());System.exit(1);}}
  static void fresh(Path root)throws Exception{
    if(!root.startsWith(REPO)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORAGE_ROOT_NOT_CONTROLLED");
    for(Path p=root;p!=null&&p.startsWith(REPO);p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalStateException("STORAGE_REPARSE_POINT");
    try(var contents=Files.list(root)){if(contents.findAny().isPresent())throw new IllegalStateException("FRESH_STORAGE_ROOT_NOT_EMPTY");}
    Path staging=Files.createDirectory(root.resolve(".staging")),objects=Files.createDirectory(root.resolve("objects"));
    String id=".proof-"+UUID.randomUUID();Path source=staging.resolve(id),other=staging.resolve(id+"-other"),link=objects.resolve(id);
    byte[] payload="fresh-test-storage:synthetic-proof:𠀀".getBytes(StandardCharsets.UTF_8);byte[] sha=MessageDigest.getInstance("SHA-256").digest(payload);
    try{
      Files.write(source,payload,StandardOpenOption.CREATE_NEW);Files.write(other,"other".getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
      Files.createLink(link,source);
      if(!Files.isSameFile(link,source)||!Arrays.equals(sha,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(link))))throw new IllegalStateException("HARDLINK_SHA_OR_IDENTITY_FAILURE");
      boolean protectedTarget=false;try{Files.createLink(link,other);}catch(FileAlreadyExistsException expected){protectedTarget=true;}
      if(!protectedTarget||!Arrays.equals(sha,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(link))))throw new IllegalStateException("HARDLINK_OVERWROTE_EXISTING_TARGET");
    }finally{Files.deleteIfExists(link);Files.deleteIfExists(source);Files.deleteIfExists(other);}
  }
}
