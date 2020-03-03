package org.embulk.input.zendesk.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.embulk.EmbulkTestRuntime;

import org.embulk.config.TaskReport;
import org.embulk.input.zendesk.RecordImporter;
import org.embulk.input.zendesk.ZendeskInputPlugin;
import org.embulk.input.zendesk.clients.ZendeskRestClient;
import org.embulk.input.zendesk.utils.ZendeskTestHelper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestZendeskCustomObjectService
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    private ZendeskRestClient zendeskRestClient;

    private ZendeskCustomObjectService zendeskCustomObjectService;

    private RecordImporter recordImporter;

    @Before
    public void prepare()
    {
        zendeskRestClient = mock(ZendeskRestClient.class);
        recordImporter = mock(RecordImporter.class);
    }

    @Test
    public void testGetListPathForObjectRecord()
    {
        setup("object_records.yml");
        List<String> expectedStrings = Arrays.asList(
                "https://abc.zendesk.com/api/sunshine/objects/records?type=account&per_page=1000",
                "https://abc.zendesk.com/api/sunshine/objects/records?type=user&per_page=1000"
        );

        zendeskCustomObjectService.addRecordToImporter(0, recordImporter);
        final ArgumentCaptor<String> actualString = ArgumentCaptor.forClass(String.class);
        verify(zendeskRestClient, times(2)).doGet(actualString.capture(), any(), anyBoolean());
        assertTrue(actualString.getAllValues().contains(expectedStrings.get(0)));
        assertTrue(actualString.getAllValues().contains(expectedStrings.get(1)));
    }

    @Test
    public void testGetListPathForRelationshipRecord()
    {
        setup("relationship_records.yml");
        String expectedStrings = "https://abc.zendesk.com/api/sunshine/relationships/records?type=ticket_to_account&per_page=1000";
        zendeskCustomObjectService.addRecordToImporter(0, recordImporter);
        final ArgumentCaptor<String> actualString = ArgumentCaptor.forClass(String.class);
        verify(zendeskRestClient).doGet(actualString.capture(), any(), anyBoolean());
        assertEquals(expectedStrings, actualString.getValue());
    }

    @Test
    public void testAddRecordToImporterObjectRecord()
    {
        ZendeskTestHelper.setPreviewMode(runtime, false);
        setup("object_records.yml");
        loadData("data/object_records.json");
        zendeskCustomObjectService.addRecordToImporter(0, recordImporter);
        // 2 types - each type 2 records
        verify(recordImporter, times(4)).addRecord(any());
    }

    @Test
    public void testAddRecordToImporterRelationShipRecord()
    {
        ZendeskTestHelper.setPreviewMode(runtime, false);
        setup("relationship_records.yml");
        loadData("data/relationship_records.json");
        zendeskCustomObjectService.addRecordToImporter(0, recordImporter);
        // 7 records
        verify(recordImporter, times(7)).addRecord(any());
    }

    @Test
    public void testGetData()
    {
        setup("object_records.yml");
        loadData("data/object_records.json");
        JsonNode jsonNode = zendeskCustomObjectService.getDataFromPath("https://abc.zendesk.com/api/sunshine/objects/records?type=user&per_page=1000", 0, true, 0);
        assertFalse(jsonNode.isNull());
        assertTrue(jsonNode.has("data"));
        assertTrue(jsonNode.get("data").isArray());

        setup("relationship_records.yml");
        loadData("data/relationship_records.json");
        jsonNode = zendeskCustomObjectService.getDataFromPath("https://abc.zendesk.com/api/sunshine/relationships/records?type=user&per_page=1000", 0, true, 0);
        assertFalse(jsonNode.isNull());
        assertTrue(jsonNode.has("data"));
        assertTrue(jsonNode.get("data").isArray());
    }

    @Test
    public void testAddRecordToImporterInPreviewObjectRecord()
    {
        ZendeskTestHelper.setPreviewMode(runtime, true);
        setup("object_records.yml");
        loadData("data/object_records.json");
        TaskReport taskReport = zendeskCustomObjectService.addRecordToImporter(0, recordImporter);

        // expect to be break and don't import all records
        verify(recordImporter, atMost(3)).addRecord(any());
        Assert.assertTrue(taskReport.isEmpty());
    }

    @Test
    public void testAddRecordToImporterInPreviewRelationshipRecord()
    {
        ZendeskTestHelper.setPreviewMode(runtime, true);
        setup("relationship_records.yml");
        loadData("data/relationship_records.json");
        TaskReport taskReport = zendeskCustomObjectService.addRecordToImporter(0, recordImporter);
        // 1 type contain data and break
        verify(recordImporter, times(1)).addRecord(any());
        Assert.assertTrue(taskReport.isEmpty());
    }

    private void loadData(String fileName)
    {
        JsonNode dataJson = ZendeskTestHelper.getJsonFromFile(fileName);
        when(zendeskRestClient.doGet(any(), any(), anyBoolean())).thenReturn(dataJson.toString());
    }

    private void setupZendeskSupportAPIService(ZendeskInputPlugin.PluginTask task)
    {
        zendeskCustomObjectService = spy(new ZendeskCustomObjectService(task));
        when(zendeskCustomObjectService.getZendeskRestClient()).thenReturn(zendeskRestClient);
    }

    private void setup(String file)
    {
        ZendeskInputPlugin.PluginTask task = ZendeskTestHelper.getConfigSource(file).loadConfig(ZendeskInputPlugin.PluginTask.class);
        setupZendeskSupportAPIService(task);
    }
}
