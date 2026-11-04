import java.nio.file.*;
public class ContractTestStorageProbeTest {
  public static void main(String[] args)throws Exception{
    Path root=Files.createTempDirectory(ContractTestStorageProbe.REPO.resolve(".runtime/contract-repair-test"),"storage-guard-test-");
    try{
      ContractTestStorageProbe.fresh(root);assert Files.isDirectory(root.resolve("objects"));assert Files.isDirectory(root.resolve(".staging"));
      boolean rejected=false;try{ContractTestStorageProbe.fresh(root);}catch(IllegalStateException expected){rejected=true;}assert rejected;
      System.out.println("WINDOWS_STORAGE_GUARD_TESTS=PASS HARDLINK_CREATE_ONLY_SHA_AND_NONEMPTY_ROOT_REJECTION");
    }finally{Files.delete(root.resolve("objects"));Files.delete(root.resolve(".staging"));Files.delete(root);}
  }
}
