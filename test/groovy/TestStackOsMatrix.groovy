import com.lesfurets.jenkins.unit.BasePipelineTest

class TestExampleJob extends BasePipelineTest {
        @Test
        void should_execute_without_errors() throws Exception {
            def script = loadScript("job/stack_os_matrix.jenkins")
            script.execute()
            printCallStack()
        }
}
