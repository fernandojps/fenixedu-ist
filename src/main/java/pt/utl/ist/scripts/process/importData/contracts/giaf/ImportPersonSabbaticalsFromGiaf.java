package pt.utl.ist.scripts.process.importData.contracts.giaf;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.sourceforge.fenixedu.domain.Employee;
import net.sourceforge.fenixedu.domain.Person;
import net.sourceforge.fenixedu.domain.personnelSection.contracts.GiafProfessionalData;
import net.sourceforge.fenixedu.domain.personnelSection.contracts.PersonProfessionalData;
import net.sourceforge.fenixedu.domain.personnelSection.contracts.PersonProfessionalExemption;
import net.sourceforge.fenixedu.domain.personnelSection.contracts.PersonSabbatical;
import net.sourceforge.fenixedu.persistenceTier.ExcepcaoPersistencia;
import net.sourceforge.fenixedu.persistenceTierOracle.Oracle.PersistentSuportGiaf;
import org.apache.commons.lang.StringUtils;

import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.scheduler.annotation.Task;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

@Task(englishTitle = "ImportPersonSabbaticalsFromGiaf")
public class ImportPersonSabbaticalsFromGiaf extends ImportFromGiaf {
    final static DateTimeFormatter dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd");

    final static DateTimeFormatter dateTimeFormat = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss");

    public ImportPersonSabbaticalsFromGiaf() {

    }

    @Override
    public void runTask() {
        getLogger().debug("Start ImportPersonSabbaticalsFromGiaf");
        try {
            PersistentSuportGiaf oracleConnection = PersistentSuportGiaf.getInstance();
            Map<Integer, Employee> employeesMap = getEmployeesMap();
            String query = getQuery();
            getLogger().debug(query);
            PreparedStatement preparedStatement = oracleConnection.prepareStatement(query);
            ResultSet result = preparedStatement.executeQuery();
            Set<Person> importedButInvalid = new HashSet<Person>();
            int count = 0;
            int news = 0;
            int notImported = 0;
            int dontExist = 0;
            while (result.next()) {
                count++;
                String numberString = result.getString("emp_num");
                Person person = getPerson(employeesMap, numberString);
                if (person == null) {
                    getLogger().info("Invalid person with number: " + numberString);
                    dontExist++;
                    continue;
                }
                PersonProfessionalData personProfessionalData = person.getPersonProfessionalData();
                if (personProfessionalData == null) {
                    getLogger().info("Empty personProfessionalData: " + numberString);
                    dontExist++;
                    continue;
                }
                GiafProfessionalData giafProfessionalData =
                        personProfessionalData.getGiafProfessionalDataByGiafPersonIdentification(numberString);
                if (giafProfessionalData == null) {
                    getLogger().info("Empty giafProfessionalData: " + numberString);
                    dontExist++;
                    continue;
                }
                String beginDateString = result.getString("DATA_INICIO");
                LocalDate beginDate = null;
                if (!StringUtils.isEmpty(beginDateString)) {
                    beginDate = new LocalDate(Timestamp.valueOf(beginDateString));
                }
                if (beginDate == null) {
                    getLogger().info("Empty beginDate. Person number: " + numberString);
                    importedButInvalid.add(person);
                }
                String endDateString = result.getString("DATA_FIM");
                LocalDate endDate = null;
                if (!StringUtils.isEmpty(endDateString)) {
                    endDate = new LocalDate(Timestamp.valueOf(endDateString));
                }
                if (endDate != null) {
                    if (beginDate != null && beginDate.isAfter(endDate)) {
                        getLogger().info(
                                "BeginDate after EndDate. Person number: " + numberString + " begin: " + beginDate + " end: "
                                        + endDate);
                        importedButInvalid.add(person);
                    }
                }
                String creationDateString = result.getString("data_criacao");
                if (StringUtils.isEmpty(creationDateString)) {
                    getLogger().info("Empty creationDate. Person number: " + numberString);
                    notImported++;
                    continue;
                }
                DateTime creationDate = new DateTime(Timestamp.valueOf(creationDateString));

                DateTime modifiedDate = null;
                String modifiedDateString = result.getString("data_alteracao");
                if (!StringUtils.isEmpty(modifiedDateString)) {
                    modifiedDate = new DateTime(Timestamp.valueOf(modifiedDateString));
                }

                if (!hasPersonSabbatical(giafProfessionalData, beginDate, endDate, creationDate, modifiedDate)) {
                    new PersonSabbatical(giafProfessionalData, beginDate, endDate, creationDate, modifiedDate);
                    news++;
                }
            }
            result.close();
            preparedStatement.close();

            int deleted = 0;
            int totalInFenix = 0;
            int repeted = 0;
            for (GiafProfessionalData giafProfessionalData : Bennu.getInstance().getGiafProfessionalDataSet()) {
                for (PersonProfessionalExemption personProfessionalExemption : giafProfessionalData
                        .getPersonProfessionalExemptions()) {
                    if (personProfessionalExemption instanceof PersonSabbatical
                            && personProfessionalExemption.getAnulationDate() == null) {
                        PersonSabbatical personSabbatical = (PersonSabbatical) personProfessionalExemption;
                        int countThisPersonSabbaticalOnGiaf = countThisPersonSabbaticalOnGiaf(oracleConnection, personSabbatical);
                        if (countThisPersonSabbaticalOnGiaf == 0) {
                            personSabbatical.setAnulationDate(new DateTime());
                            deleted++;
                        } else {
                            totalInFenix++;
                            if (countThisPersonSabbaticalOnGiaf > 1) {
                                repeted += countThisPersonSabbaticalOnGiaf - 1;
                            }
                        }
                    }
                }
            }

            oracleConnection.closeConnection();
            getLogger().info("Total GIAF: " + count);
            getLogger().info("New: " + news);
            getLogger().info("Deleted: " + deleted);
            getLogger().info("Not imported: " + notImported);
            getLogger().info("Imported with errors: " + importedButInvalid.size());
            getLogger().info("Repeted: " + repeted);
            getLogger().info("Invalid person or situation: " + dontExist);
            getLogger().info("Total Fénix: " + totalInFenix);
            getLogger().info("Total Fénix without errors: " + (totalInFenix - importedButInvalid.size()));
            getLogger().info("Missing in Fénix: " + (count - totalInFenix));

        } catch (ExcepcaoPersistencia e) {
            getLogger().info("ImportPersonSabbaticalsFromGiaf -  ERRO ExcepcaoPersistencia");
            throw new Error(e);
        } catch (SQLException e) {
            getLogger().info("ImportPersonSabbaticalsFromGiaf -  ERRO SQLException");
            throw new Error(e);
        }
        getLogger().debug("The end");
    }

    private int countThisPersonSabbaticalOnGiaf(PersistentSuportGiaf oracleConnection, PersonSabbatical personSabbatical)
            throws ExcepcaoPersistencia, SQLException {
        String query = getSabbaticalQuery(personSabbatical);
        PreparedStatement preparedStatement = null;
        ResultSet result = null;
        try {
            preparedStatement = oracleConnection.prepareStatement(query);
            result = preparedStatement.executeQuery();
            if (result.next()) {
                int count = result.getInt("cont");
                if (count > 0) {
                    if (count > 1) {
                        getLogger().info(
                                "---> " + count + " ---> "
                                        + personSabbatical.getGiafProfessionalData().getGiafPersonIdentification());
                    }
                    return count;
                }
            }
            getLogger().info(query);
            return 0;
        } catch (ExcepcaoPersistencia e) {
            getLogger().info("ImportPersonSabbaticalsFromGiaf -  ERRO ExcepcaoPersistencia hasPersonSabbaticalOnGiaf");
            throw new Error(e);
        } catch (SQLException e) {
            getLogger().info("ImportPersonSabbaticalsFromGiaf -  ERRO SQLException hasPersonSabbaticalOnGiaf " + query);
            throw new Error(e);
        } finally {
            if (result != null) {
                result.close();
            }
            preparedStatement.close();
        }
    }

    private String getSabbaticalQuery(PersonSabbatical personSabbatical) {
        StringBuilder query = new StringBuilder();

        query.append("select count(*) as cont from SLDSABATICA where emp_num=");
        query.append(personSabbatical.getGiafProfessionalData().getGiafPersonIdentification());
        if (personSabbatical.getBeginDate() != null) {
            query.append(" and DATA_INICIO=to_date('");
            query.append(dateFormat.print(personSabbatical.getBeginDate()));
            query.append("','YYYY-MM-DD')");
        } else {
            query.append(" and DATA_INICIO is null");
        }
        if (personSabbatical.getEndDate() != null) {
            query.append(" and DATA_FIM=to_date('");
            query.append(dateFormat.print(personSabbatical.getEndDate()));
            query.append("','YYYY-MM-DD')");
        } else {
            query.append(" and DATA_FIM is null");
        }

        query.append(" and data_criacao=to_date('");
        query.append(dateTimeFormat.print(personSabbatical.getCreationDate()));
        query.append("','YYYY-MM-DD HH24:mi:ss')");
        if (personSabbatical.getModifiedDate() != null) {
            query.append(" and data_alteracao=to_date('");
            query.append(dateTimeFormat.print(personSabbatical.getModifiedDate()));
            query.append("','YYYY-MM-DD HH24:mi:ss')");
        } else {
            query.append("and data_alteracao is null");
        }
        return query.toString();

    }

    private boolean hasPersonSabbatical(GiafProfessionalData giafProfessionalData, LocalDate beginDate, LocalDate endDate,
            DateTime creationDate, DateTime modifiedDate) {
        for (PersonProfessionalExemption personProfessionalExemption : giafProfessionalData.getPersonProfessionalExemptions()) {
            if (personProfessionalExemption.getAnulationDate() == null) {
                if (personProfessionalExemption instanceof PersonSabbatical) {
                    PersonSabbatical personSabbatical = (PersonSabbatical) personProfessionalExemption;
                    if (equal(beginDate, personSabbatical.getBeginDate()) && equal(endDate, personSabbatical.getEndDate())
                            && equal(creationDate, personSabbatical.getCreationDate())
                            && equal(modifiedDate, personSabbatical.getModifiedDate())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected String getQuery() {
        return "SELECT sabatica.DATA_FIM, sabatica.DATA_INICIO, sabatica.EMP_NUM, sabatica.data_criacao, sabatica.data_alteracao FROM SLDSABATICA sabatica";
    }

}