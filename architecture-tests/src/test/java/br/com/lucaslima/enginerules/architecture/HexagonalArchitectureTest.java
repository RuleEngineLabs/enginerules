package br.com.lucaslima.enginerules.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class HexagonalArchitectureTest {

    private static final String ROOT = "br.com.lucaslima.enginerules";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void domainDependsOnNothingButTheJdk() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages(ROOT + ".domain..", "java..")
                .check(CLASSES);
    }

    @Test
    void applicationDependsOnlyOnDomainAndTheJdk() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat()
                .resideOutsideOfPackages(ROOT + ".application..", ROOT + ".domain..", "java..")
                .check(CLASSES);
    }

    @Test
    void coreNeverSeesAdaptersOrApps() {
        noClasses().that().resideInAnyPackage(ROOT + ".domain..", ROOT + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".adapter..", ROOT + ".app..")
                .check(CLASSES);
    }

    @Test
    void adaptersDoNotDependOnEachOtherExceptTheSharedJsonCodec() {
        slices().matching(ROOT + ".adapter.(*)..")
                .should().notDependOnEachOther()
                .ignoreDependency(com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
                        com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(ROOT + ".adapter.json.."))
                .check(CLASSES);
    }

    @Test
    void onlyAppsAndTheWebAdapterKnowSpring() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".app..", ROOT + ".adapter.web..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .check(CLASSES);
    }

    @Test
    void onlyTheGrpcAdapterAndAppsKnowGrpcAndProtobuf() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".adapter.grpc..", ROOT + ".app..")
                .should().dependOnClassesThat().resideInAnyPackage("io.grpc..", "com.google.protobuf..")
                .check(CLASSES);
    }

    @Test
    void onlyTheAwsAdapterAndAppsKnowTheAwsSdk() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".adapter.persistence.aws..", ROOT + ".app..")
                .should().dependOnClassesThat().resideInAPackage("software.amazon..")
                .check(CLASSES);
    }

    @Test
    void domainSubdomainsAreFreeOfCycles() {
        slices().matching(ROOT + ".domain.(*)..").should().beFreeOfCycles().check(CLASSES);
    }
}
