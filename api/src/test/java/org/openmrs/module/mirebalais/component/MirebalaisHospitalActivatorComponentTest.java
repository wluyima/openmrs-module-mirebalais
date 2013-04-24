/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.mirebalais.component;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.Location;
import org.openmrs.LocationAttributeType;
import org.openmrs.Person;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.addresshierarchy.AddressField;
import org.openmrs.module.addresshierarchy.AddressHierarchyLevel;
import org.openmrs.module.addresshierarchy.service.AddressHierarchyService;
import org.openmrs.module.emr.EmrConstants;
import org.openmrs.module.emr.radiology.RadiologyConstants;
import org.openmrs.module.emrapi.account.AccountDomainWrapper;
import org.openmrs.module.emrapi.account.AccountService;
import org.openmrs.module.metadatasharing.ImportedPackage;
import org.openmrs.module.metadatasharing.api.MetadataSharingService;
import org.openmrs.module.mirebalais.MetadataPackageConfig;
import org.openmrs.module.mirebalais.MirebalaisGlobalProperties;
import org.openmrs.module.mirebalais.MirebalaisHospitalActivator;
import org.openmrs.module.pacsintegration.PacsIntegrationConstants;
import org.openmrs.module.patientregistration.PatientRegistrationGlobalProperties;
import org.openmrs.module.providermanagement.api.ProviderManagementService;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.test.SkipBaseSetup;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.validator.ValidateUtil;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@SkipBaseSetup          // note that we skip the base setup because we don't want to include the standard test data
public class MirebalaisHospitalActivatorComponentTest extends BaseModuleContextSensitiveTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private SchedulerService schedulerService;

    @Autowired
    private AdministrationService adminService;

	private MirebalaisHospitalActivator activator;
	
	@Before
	public void beforeEachTest() throws Exception {
		initializeInMemoryDatabase();
		executeDataSet("requiredDataTestDataset.xml");
		executeDataSet("globalPropertiesTestDataset.xml");
        executeDataSet("mirebalaisProviderIdentifierGeneratorComponentTestDataset.xml");
		authenticate();
		activator = new MirebalaisHospitalActivator();
        activator.willRefreshContext();
        activator.contextRefreshed();
        activator.willStart();
		activator.started();
		
	}
	
	@Test
	public void testThatActivatorDoesAllSetup() throws Exception {
		verifyMetadataPackagesConfigured(activator);
		verifyGlobalPropertiesConfigured();
		verifyPatientRegistrationConfigured();
		verifyPacsIntegrationGlobalPropertiesConfigured();
		verifyAddressHierarchyLevelsCreated();
		verifyAddressHierarchyLoaded();
		verifyLocationAttributeNotOverwritten();
        verifyMirebalaisProviderIdentifierGeneratorConfigured();
        verifyCloseStalePullRequestsTaskScheduledAndStarted();
	}
	
	private void verifyPatientRegistrationConfigured() {
		List<Method> failingMethods = new ArrayList<Method>();
		for (Method method : PatientRegistrationGlobalProperties.class.getMethods()) {
			if (method.getName().startsWith("GLOBAL_PROPERTY") && method.getParameterTypes().length == 0) {
				try {
					method.invoke(null);
				}
				catch (Exception ex) {
					failingMethods.add(method);
				}
			}
		}
		
		if (failingMethods.size() > 0) {
			String errorMessage = "Some Patient Registration global properties are not configured correctly. See these methods in the PatientRegistrationGlobalProperties class";
			for (Method method : failingMethods) {
				errorMessage += "\n" + method.getName();
			}
			Assert.fail(errorMessage);
		}
	}
	
	private void verifyMetadataPackagesConfigured(MirebalaisHospitalActivator activator) throws Exception {
		
		MetadataSharingService metadataSharingService = Context.getService(MetadataSharingService.class);
		
		// To catch the (common) case where someone gets the groupUuid wrong, we look for any installed packages that
		// we are not expecting
		
		List<String> groupUuids = new ArrayList<String>();
		
		for (MetadataPackageConfig metadataPackage : activator.getCurrentMetadataVersions()) {
			groupUuids.add(metadataPackage.getGroupUuid());
		}
		
		for (ImportedPackage importedPackage : metadataSharingService.getAllImportedPackages()) {
			if (!groupUuids.contains(importedPackage.getGroupUuid())) {
				Assert.fail("Found a package with an unexpected groupUuid. Name: " + importedPackage.getName()
				        + " , groupUuid: " + importedPackage.getGroupUuid());
			}
		}
		
		for (MetadataPackageConfig metadataPackage : activator.getCurrentMetadataVersions()) {
			ImportedPackage installedPackage = metadataSharingService.getImportedPackageByGroup(metadataPackage
			        .getGroupUuid());
			Integer actualVersion = installedPackage == null ? null : installedPackage.getVersion();
			assertEquals("Failed to install " + metadataPackage.getFilenameBase() + ". Expected version: "
			        + metadataPackage.getVersion() + " Actual version: " + actualVersion, metadataPackage.getVersion(),
			    actualVersion);
		}
		
		// Verify a few pieces of sentinel data that should have been in the packages
        ConceptService conceptService = Context.getConceptService();
		Assert.assertNotNull(Context.getLocationService().getLocation("Mirebalais Hospital"));
		Assert.assertNotNull((Context.getOrderService().getOrderTypeByUuid(Context.getAdministrationService()
		        .getGlobalProperty(RadiologyConstants.GP_RADIOLOGY_TEST_ORDER_TYPE))));
        Assert.assertNotNull((conceptService.getConceptByMapping("TEMPERATURE (C)", "PIH")));
		Assert.assertNotNull(Context.getService((ProviderManagementService.class)).getProviderRoleByUuid("61eed524-4547-4228-a3ac-631fe1628a5e"));

        // Regression test for META-323
        {
            assertThat(conceptService.getConceptByUuid("06cc08fb-414a-46e6-8c20-136535609812"), notNullValue());
            assertThat(conceptService.getConceptByUuid("06cc08fb-414a-46e6-8c20-136535609812").getName().getName(), is("Fièvre rhumatismale, sans attente cardiaque"));
            assertThat(conceptService.getConceptByUuid("006ab3b2-a0ea-45bf-b495-83e06f26f87a"), notNullValue());
            assertThat(conceptService.getConceptByUuid("006ab3b2-a0ea-45bf-b495-83e06f26f87a").getName().getName(), is("Fievre rheumatique aigue"));
        }

		// this doesn't strictly belong here, but we include it as an extra sanity check on the MDS module
		for (Concept concept : conceptService.getAllConcepts()) {
			ValidateUtil.validate(concept);
		}
	}
	
	private void verifyGlobalPropertiesConfigured() throws Exception {
        assertEquals("fr", Context.getAdministrationService().getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_DEFAULT_LOCALE));
		assertEquals(
		    "<org.openmrs.layout.web.address.AddressTemplate><nameMappings class=\"properties\"><property name=\"country\" value=\"mirebalais.address.country\"/><property name=\"stateProvince\" value=\"mirebalais.address.stateProvince\"/><property name=\"cityVillage\" value=\"mirebalais.address.cityVillage\"/><property name=\"address3\" value=\"mirebalais.address.neighborhoodCell\"/><property name=\"address1\" value=\"mirebalais.address.address1\"/><property name=\"address2\" value=\"mirebalais.address.address2\"/></nameMappings><sizeMappings class=\"properties\"><property name=\"country\" value=\"40\"/><property name=\"stateProvince\" value=\"40\"/><property name=\"cityVillage\" value=\"40\"/><property name=\"address3\" value=\"60\"/><property name=\"address1\" value=\"60\"/><property name=\"address2\" value=\"60\"/></sizeMappings><elementDefaults class=\"properties\"><property name=\"country\" value=\"Haiti\"/></elementDefaults><lineByLineFormat><string>address2</string><string>address1</string><string>address3 cityVillage</string><string>stateProvince country</string></lineByLineFormat></org.openmrs.layout.web.address.AddressTemplate>",
		    MirebalaisGlobalProperties.ADDRESS_LAYOUT_FORMAT());
        assertEquals("false", Context.getAdministrationService().getGlobalProperty(OpenmrsConstants.GP_PASSWORD_REQUIRES_UPPER_AND_LOWER_CASE));
        assertEquals("false", Context.getAdministrationService().getGlobalProperty(OpenmrsConstants.GP_PASSWORD_REQUIRES_NON_DIGIT));
        assertEquals("false", Context.getAdministrationService().getGlobalProperty(OpenmrsConstants.GP_PASSWORD_REQUIRES_DIGIT));
        assertEquals("8", Context.getAdministrationService().getGlobalProperty(OpenmrsConstants.GP_PASSWORD_MINIMUM_LENGTH));
    }
	
	private void verifyPacsIntegrationGlobalPropertiesConfigured() throws Exception {
		assertEquals("a541af1e-105c-40bf-b345-ba1fd6a59b85", Context.getAdministrationService().getGlobalProperty(
		    PacsIntegrationConstants.GP_PATIENT_IDENTIFIER_TYPE_UUID));
		assertEquals("en", Context.getAdministrationService().getGlobalProperty(
		    PacsIntegrationConstants.GP_DEFAULT_LOCALE));
		assertEquals("Mirebalais", Context.getAdministrationService().getGlobalProperty(
		    PacsIntegrationConstants.GP_SENDING_FACILITY));
		assertEquals("2889f378-f287-40a5-ac9c-ce77ee963ed7", Context.getAdministrationService().getGlobalProperty(
		    PacsIntegrationConstants.GP_PROCEDURE_CODE_CONCEPT_SOURCE_UUID));
	}
	
	private void verifyAddressHierarchyLevelsCreated() throws Exception {
		AddressHierarchyService ahService = Context.getService(AddressHierarchyService.class);
		
		// assert that we now have six address hierarchy levels
		assertEquals(new Integer(6), ahService.getAddressHierarchyLevelsCount());
		
		// make sure they are mapped correctly
		List<AddressHierarchyLevel> levels = ahService.getOrderedAddressHierarchyLevels(true);
		assertEquals(AddressField.COUNTRY, levels.get(0).getAddressField());
		assertEquals(AddressField.STATE_PROVINCE, levels.get(1).getAddressField());
		assertEquals(AddressField.CITY_VILLAGE, levels.get(2).getAddressField());
		assertEquals(AddressField.ADDRESS_3, levels.get(3).getAddressField());
		assertEquals(AddressField.ADDRESS_1, levels.get(4).getAddressField());
		assertEquals(AddressField.ADDRESS_2, levels.get(5).getAddressField());
		
	}
	
	private void verifyAddressHierarchyLoaded() throws Exception {
		AddressHierarchyService ahService = Context.getService(AddressHierarchyService.class);
		
		// we should now have 26000+ address hierarchy entries
		Assert.assertTrue(ahService.getAddressHierarchyEntryCount() > 26000);
		
		assertEquals(1, ahService.getAddressHierarchyEntriesAtTopLevel().size());
		assertEquals("Haiti", ahService.getAddressHierarchyEntriesAtTopLevel().get(0).getName());
        assertEquals(5, Integer.parseInt(adminService.getGlobalProperty(MirebalaisGlobalProperties.INSTALLED_ADDRESS_HIERARCHY_VERSION)) );
	}
	
	private void verifyLocationAttributeNotOverwritten() throws Exception {
		// make sure that when importing the location metadata package, the location
		// attribute we defined in the requiredDataTestDataset has not been overwritten
		
		Location location = Context.getLocationService().getLocation(1001);
		LocationAttributeType type = Context.getLocationService().getLocationAttributeType(1001);
		assertEquals(1, location.getActiveAttributes(type).size());
		assertEquals("Mark", location.getActiveAttributes(type).get(0).getValue().toString());
	}

    private void verifyMirebalaisProviderIdentifierGeneratorConfigured() {
        Person person = Context.getPersonService().getPerson(2);
        AccountDomainWrapper account = accountService.getAccountByPerson(person);
        accountService.saveAccount(account);
        assertEquals("MCEPM", account.getProvider().getIdentifier());
    }

    private void verifyCloseStalePullRequestsTaskScheduledAndStarted() {

        TaskDefinition taskDefinition = schedulerService.getTaskByName(EmrConstants.TASK_CLOSE_STALE_PULL_REQUESTS);

        assertNotNull(taskDefinition);
        assertTrue(taskDefinition.getStarted());
        assertTrue(taskDefinition.getStartOnStartup());
        assertEquals(new Long(3600), taskDefinition.getRepeatInterval());

    }
}
