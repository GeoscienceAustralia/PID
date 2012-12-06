package csiro.pidsvc.mappingstore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import javax.xml.XMLConstants;
import javax.xml.bind.ValidationException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang.StringEscapeUtils;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import csiro.pidsvc.helper.Http;
import csiro.pidsvc.helper.Stream;
import csiro.pidsvc.helper.URI;
import csiro.pidsvc.mappingstore.action.List;
import csiro.pidsvc.mappingstore.condition.AbstractCondition;
import csiro.pidsvc.mappingstore.condition.ConditionContentType;

public class Manager
{
	protected Connection _connection = null;
	
	public class MappingMatchResults
	{
		public static final int NULL = -1;

		public final int DefaultActionId;
		public final AbstractCondition Condition;
		public final Object AuxiliaryData;
		
		public MappingMatchResults(int defaultActionId, AbstractCondition condition, Object auxiliaryData)
		{
			DefaultActionId = defaultActionId;
			Condition = condition;
			AuxiliaryData = auxiliaryData;
		}
		
		public boolean success()
		{
			return DefaultActionId != MappingMatchResults.NULL || Condition != null || AuxiliaryData != null;
		}
	}

	protected interface ICallback
	{
		public String process(InputStream inputStream) throws Exception;
	}

	public Manager() throws NamingException, SQLException, IOException
	{
		Properties properties = new Properties(); 
		properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("../mappingstore.properties"));

		InitialContext initCtx = new InitialContext(); 
		Context envCtx = (Context)initCtx.lookup("java:comp/env"); 
		DataSource ds = (DataSource)envCtx.lookup(properties.getProperty("jndiReferenceName"));
		
		_connection = ds.getConnection();
	}

	public void close()
	{
		try
		{
			_connection.close();
		}
		catch (SQLException e)
		{
			e.printStackTrace();
		}
		finally
		{
			_connection = null;
		}
	}

	/**************************************************************************
	 *  Generic processing methods.
	 */

	protected String processGenericXmlCommand(InputStream inputStream, String xmlSchemaResourcePath, String xsltResourcePath) throws SaxonApiException, SQLException, IOException, ValidationException
	{
		String inputData = Stream.readInputStream(inputStream);

		// Validate request.
		if (xmlSchemaResourcePath != null)
		{
			try
			{
				SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
				schemaFactory.setResourceResolver(new LSResourceResolver() {
					@Override
					public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI)
					{
						return new XsdSchemaResolver(type, namespaceURI, publicId, systemId, baseURI);
					}
				});
	
				Schema schema = schemaFactory.newSchema(new StreamSource(getClass().getResourceAsStream(xmlSchemaResourcePath)));
				Validator validator = schema.newValidator();
				validator.validate(new StreamSource(new StringReader(inputData))); 
			}
			catch (SAXException ex)
			{
				ex.printStackTrace();
				throw new ValidationException("Unknown format.", ex);
			}
		}

		// Generate SQL query.
		Processor			processor = new Processor(false);
		XsltCompiler		xsltCompiler = processor.newXsltCompiler();
		InputStream			inputSqlGen = getClass().getResourceAsStream(xsltResourcePath);
		XsltExecutable		xsltExec = xsltCompiler.compile(new StreamSource(inputSqlGen));
		XsltTransformer		transformer = xsltExec.load();

		StringWriter swSqlQuery = new StringWriter();
		transformer.setInitialContextNode(processor.newDocumentBuilder().build(new StreamSource(new StringReader(inputData))));
		transformer.setDestination(new Serializer(swSqlQuery));
		transformer.transform();

		// Update mappings in the database.
		Statement st = null;
		try
		{
			String sqlQuery = swSqlQuery.toString();
			st = _connection.createStatement();
			st.execute(sqlQuery);

			Pattern re = Pattern.compile("^--((?:OK|ERROR): .*)", Pattern.CASE_INSENSITIVE);
			Matcher m = re.matcher(sqlQuery);

			if (m.find())
				return m.group(1);
		}
		finally
		{
			if (st != null)
				st.close();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	protected String unwrapCompressedBackupFile(HttpServletRequest request, ICallback callback)
	{
		java.util.List<FileItem>	fileList = null;
		GZIPInputStream				gis = null;

		try
		{
			DiskFileItemFactory fileItemFactory = new DiskFileItemFactory();

			// Set the size threshold, above which content will be stored on disk.
			fileItemFactory.setSizeThreshold(1 * 1024 * 1024); // 1 MB
//			fileItemFactory.setSizeThreshold(100 * 1024); // 100 KB

			// Set the temporary directory to store the uploaded files of size above threshold.
			fileItemFactory.setRepository(new File(System.getProperty("java.io.tmpdir")));

			ServletFileUpload uploadHandler = new ServletFileUpload(fileItemFactory);

			fileList = uploadHandler.parseRequest(request);
			for (FileItem item : fileList)
			{
				if (item.isFormField())
					continue;

				gis = new GZIPInputStream(item.getInputStream());
//				String fileContent = Stream.readInputStream(gis);
				String ret = callback.process(gis);
				gis.close();
				
				// Process the first uploaded file only.
				return ret;
			}
		}
		catch (Exception ex)
		{
			String msg = ex.getMessage();
			if (msg != null && msg.equalsIgnoreCase("Not in GZIP format"))
				return "ERROR: Unknown file format.";
			else
				return "ERROR: " + (msg == null ? "Something went wrong." : msg);
		}
		finally
		{
			try
			{
				// Close the stream.
				gis.close();
			}
			catch (Exception ex)
			{
			}
			if (fileList != null)
			{
				// Delete all uploaded files.
				for (FileItem item : fileList)
				{
					if (!item.isFormField() && !item.isInMemory())
						((DiskFileItem)item).delete();
				}
			}
		}
		return "ERROR: No file.";
	}

	/**************************************************************************
	 *  URI Mappings.
	 */

	public String createMapping(InputStream inputStream, boolean isBackup) throws SaxonApiException, SQLException, IOException, ValidationException
	{
		return processGenericXmlCommand(inputStream, "xsd/" + (isBackup ? "backup" : "mapping") + ".xsd", "xslt/import_mapping_postgresql.xslt");
	}
	
	public boolean deleteMapping(String mappingPath) throws SQLException
	{
		PreparedStatement pst = null;
		try
		{
			pst = _connection.prepareStatement("UPDATE mapping SET date_end = now() WHERE mapping_path = ? AND date_end IS NULL;");
			pst.setString(1, mappingPath);
			return pst.execute();
		}
		finally
		{
			if (pst != null)
				pst.close();
		}
	}
	
	public MappingMatchResults findExactMatch(URI uri, HttpServletRequest request) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException, InvocationTargetException
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;
		int					defaultActionId = MappingMatchResults.NULL;
		AbstractCondition	retCondition = null;
		Object				matchAuxiliaryData = null;

		try
		{
			pst = _connection.prepareStatement("SELECT mapping_id, default_action_id FROM vw_active_mapping WHERE mapping_path = ? AND type = '1:1'");
			pst.setString(1, uri.getPathNoExtension());
			
			if (pst.execute())
			{
				// First result set.
				rs = pst.getResultSet();
				if (rs.next())
				{
					// Save default action id.
					defaultActionId = rs.getInt(2);
					if (rs.wasNull())
						defaultActionId = MappingMatchResults.NULL;

					// Set auxiliary data to "true" to flag that the URI has been found.
					matchAuxiliaryData = true;

					// Find matching condition.
					retCondition = getCondition(rs.getInt(1), uri, request, matchAuxiliaryData);
				}
			}
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
		return new MappingMatchResults(defaultActionId, retCondition, matchAuxiliaryData);		
	}

	public MappingMatchResults findRegexMatch(URI uri, HttpServletRequest request) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException, IllegalArgumentException, SecurityException, InvocationTargetException, NoSuchMethodException
	{
		PreparedStatement	pst = null;
		ResultSet 			rs = null;
		int					defaultActionId = MappingMatchResults.NULL;
		AbstractCondition	retCondition = null;
		Object				matchAuxiliaryData = null;

		try
		{
			pst = _connection.prepareStatement("SELECT mapping_id, mapping_path, default_action_id FROM vw_active_mapping WHERE type = 'Regex'");
			if (pst.execute())
			{
				rs = pst.getResultSet();
				while (rs.next())
				{
					Pattern re = Pattern.compile(rs.getString(2), Pattern.CASE_INSENSITIVE);
					Matcher m = re.matcher(uri.getPathNoExtension());

					if (m.find())
					{
						// Save default action id.
						defaultActionId = rs.getInt(3);
						if (rs.wasNull())
							defaultActionId = MappingMatchResults.NULL;

						// Save auxiliary data.
						matchAuxiliaryData = re;
						
						// Find matching condition.
						retCondition = getCondition(rs.getInt(1), uri, request, matchAuxiliaryData);
						break;
					}
				}
			}
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
		
		return new MappingMatchResults(defaultActionId, retCondition, matchAuxiliaryData);
	}
	
	protected Vector<csiro.pidsvc.mappingstore.condition.Descriptor> getConditions(int mappingId) throws SQLException
	{
		PreparedStatement	pst = null;
		ResultSet 			rs = null;

		try
		{
			pst = _connection.prepareStatement("SELECT * FROM condition WHERE mapping_id = ? ORDER BY condition_id");
			pst.setInt(1, mappingId);
			
			if (!pst.execute())
				return null;

			// Get list of conditions.
			Vector<csiro.pidsvc.mappingstore.condition.Descriptor> conditions = new Vector<csiro.pidsvc.mappingstore.condition.Descriptor>();
			for (rs = pst.getResultSet(); rs.next(); conditions.add(new csiro.pidsvc.mappingstore.condition.Descriptor(rs.getInt("condition_id"), rs.getString("type"), rs.getString("match"))));
			return conditions;
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
		return null;
	}
	
	protected AbstractCondition getCondition(int mappingId, URI uri, HttpServletRequest request, Object matchAuxiliaryData) throws SQLException
	{
		// Get list of conditions.
		Vector<csiro.pidsvc.mappingstore.condition.Descriptor> conditions = getConditions(mappingId);
		if (conditions == null)
			return null;

		// Test conditions.
		try
		{
			Vector<ConditionContentType> prioritizedConditions = null;
			for (csiro.pidsvc.mappingstore.condition.Descriptor descriptor : conditions)
			{
				/*
				 * Once ContentType condition is encountered process all
				 * ContentType conditions in one go as a group.
				 */
				if (descriptor.Type.equalsIgnoreCase("ContentType"))
				{
					// Skip if ContentType conditions have already been processed.
					if (prioritizedConditions != null)
						continue;
	
					// Extract all ContentType conditions.
					prioritizedConditions = new Vector<ConditionContentType>();
					for (csiro.pidsvc.mappingstore.condition.Descriptor dctr : conditions)
					{
						if (dctr.Type.equalsIgnoreCase("ContentType"))
						{
							Class<?> impl = Class.forName("csiro.pidsvc.mappingstore.condition.Condition" + dctr.Type);
							Constructor<?> ctor = impl.getDeclaredConstructor(URI.class, HttpServletRequest.class, int.class, String.class, Object.class);
							prioritizedConditions.add((ConditionContentType)ctor.newInstance(uri, request, dctr.ID, dctr.Match, matchAuxiliaryData));
						}
					}
	
					// Find matching conditions.
					AbstractCondition matchingCondition = ConditionContentType.getMatchingCondition(prioritizedConditions);
					if (matchingCondition != null)
						return matchingCondition;

					// Continue if no matching conditions were found.
					continue;
				}

				// Process all other conditions.
				Class<?> impl = Class.forName("csiro.pidsvc.mappingstore.condition.Condition" + descriptor.Type);
				Constructor<?> ctor = impl.getDeclaredConstructor(URI.class, HttpServletRequest.class, int.class, String.class, Object.class);

				AbstractCondition condition = (AbstractCondition)ctor.newInstance(uri, request, descriptor.ID, descriptor.Match, matchAuxiliaryData);
				if (condition.matches())
					return condition;
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		return null;
	}

	public csiro.pidsvc.mappingstore.action.Descriptor getActionsByActionId(int actionId) throws SQLException
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;

		try
		{
			pst = _connection.prepareStatement("SELECT * FROM action WHERE action_id = ?");
			pst.setInt(1, actionId);
			
			rs = pst.executeQuery();
			rs.next();
			return new csiro.pidsvc.mappingstore.action.Descriptor(rs.getString("type"), rs.getString("action_name"), rs.getString("action_value"));
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
	}
	
	public List getActionsByConditionId(int conditionId) throws SQLException
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;
		List				actions = new List();

		try
		{
			pst = _connection.prepareStatement("SELECT * FROM action WHERE condition_id = ?");
			pst.setInt(1, conditionId);

			rs = pst.executeQuery();
			while (rs.next())
			{
				actions.add(new csiro.pidsvc.mappingstore.action.Descriptor(rs.getString("type"), rs.getString("action_name"), rs.getString("action_value")));
			}
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
		return actions;
	}

	/**************************************************************************
	 *  Export/import.
	 */

	public String backupDataStore(boolean fullBackup, boolean includeDeprecated) throws SQLException
	{
		String source;
		if (includeDeprecated)
			source = fullBackup ? "mapping" : "vw_latest_mapping";
		else
			source = fullBackup ? "vw_full_mapping_activeonly" : "vw_active_mapping";
		return exportMappingsImpl(null, "db", fullBackup, source, true);
	}

	public String exportMapping(int mappingId) throws SQLException
	{
		return exportMappingsImpl(mappingId, "record", false, "mapping", false);
	}

	public String exportMapping(String mappingPath, boolean fullBackup) throws SQLException
	{
		return exportMappingsImpl(mappingPath, "record", fullBackup, fullBackup ? "mapping" : "vw_latest_mapping", false);
	}

	protected String exportMappingsImpl(Object mappingIdentifier, String scope, boolean fullBackup, String source, boolean preserveDatesForDeprecatedMappings) throws SQLException
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;
		String				ret = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<backup type=\"" + (fullBackup ? "full" : "partial") + "\" scope=\"" + scope + "\" xmlns=\"urn:csiro:xmlns:pidsvc:mapping:1.0\">";
		int					defaultActionId;
		List				actions;
		Timestamp			timeStamp;
		String				buf, path;

		try
		{
			if (mappingIdentifier instanceof Integer)
			{
				pst = _connection.prepareStatement("SELECT * FROM " + source + " WHERE mapping_id = ? ORDER BY mapping_id");
				pst.setInt(1, (Integer)mappingIdentifier);
			}
			else
			{
				pst = _connection.prepareStatement("SELECT * FROM " + source + (mappingIdentifier == null ? "" : " WHERE mapping_path = ?") + " ORDER BY mapping_id");
				if (mappingIdentifier != null)
					pst.setString(1, (String)mappingIdentifier);
			}

			if (pst.execute())
			{
				for (rs = pst.getResultSet(); rs.next();)
				{
					path = rs.getString(mappingIdentifier instanceof Integer || !fullBackup ? "original_path" : "mapping_path");
					
					ret += "<mapping";

					// Time stamps are only applicable for full backups and deprecated records.
					if (fullBackup || !fullBackup && preserveDatesForDeprecatedMappings && rs.getTimestamp("date_end") != null)
					{
						timeStamp = rs.getTimestamp("date_start");
						if (timeStamp != null)
							ret += " date_start=\"" + timeStamp.toString().replace(" ", "T") + "Z\"";
						timeStamp = rs.getTimestamp("date_end");
						if (timeStamp != null)
							ret += " date_end=\"" + timeStamp.toString().replace(" ", "T") + "Z\"";
					}

					// Preserve original mapping path for full backups.
					if (fullBackup)
						ret += " original_path=\"" + rs.getString("original_path") + "\"";

					ret += ">";	// mapping

					ret += "<path>" + StringEscapeUtils.escapeXml(path) + "</path>";
					ret += "<type>" + rs.getString("type") + "</type>";

					buf = rs.getString("description");
					if (buf != null)
						ret += "<description>" + StringEscapeUtils.escapeXml(buf) + "</description>";
					buf = rs.getString("creator");
					if (buf != null)
						ret += "<creator>" + StringEscapeUtils.escapeXml(buf) + "</creator>";

					// Default action.
					defaultActionId = rs.getInt("default_action_id");
					if (!rs.wasNull())
					{
						csiro.pidsvc.mappingstore.action.Descriptor action = getActionsByActionId(defaultActionId);
						ret += "<action>";
						ret += "<type>" + action.Type + "</type>";
						if (action.Name != null)
							ret += "<name>" + StringEscapeUtils.escapeXml(action.Name) + "</name>";
						if (action.Value != null)
							ret += "<value>" + StringEscapeUtils.escapeXml(action.Value) + "</value>";
						ret += "</action>";
					}
					
					// Conditions.
					Vector<csiro.pidsvc.mappingstore.condition.Descriptor> conditions = getConditions(rs.getInt("mapping_id"));
					if (conditions != null && conditions.size() > 0)
					{
						ret += "<conditions>";
						for (csiro.pidsvc.mappingstore.condition.Descriptor condition : conditions)
						{
							ret += "<condition>";
							ret += "<type>" + condition.Type + "</type>";
							ret += "<match>" + StringEscapeUtils.escapeXml(condition.Match) + "</match>";
							

							actions = getActionsByConditionId(condition.ID);
							if (actions != null && actions.size() > 0)
							{
								ret += "<actions>";
								for (csiro.pidsvc.mappingstore.action.Descriptor action : actions)
								{
									ret += "<action>";
									ret += "<type>" + action.Type + "</type>";
									if (action.Name != null)
										ret += "<name>" + StringEscapeUtils.escapeXml(action.Name) + "</name>";
									if (action.Value != null)
										ret += "<value>" + StringEscapeUtils.escapeXml(action.Value) + "</value>";
									ret += "</action>";
								};
								ret += "</actions>";
							}
							ret += "</condition>";
						}
						ret += "</conditions>";
					}
					ret += "</mapping>";
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
		ret += "</backup>";
		return ret;
	}
	
	public String importMappings(HttpServletRequest request)
	{
		return unwrapCompressedBackupFile(request, new ICallback() {
			@Override
			public String process(InputStream inputStream) throws Exception
			{
				return createMapping(inputStream, true);
			}
		});
	}

	public boolean purgeDataStore() throws SQLException
	{
		PreparedStatement pst = null;
		try
		{
			pst = _connection.prepareStatement("TRUNCATE TABLE mapping RESTART IDENTITY CASCADE;");
			return pst.execute();
		}
		finally
		{
			if (pst != null)
				pst.close();
		}
	}

	/**************************************************************************
	 *  Settings.
	 */

	public boolean saveSettings(Map<?, ?> settings) throws SQLException
	{
		String sqlQuery = "BEGIN;\n";
		HashMap<String, String> params = new HashMap<String, String>(); 
		for (Object key : settings.keySet())
		{
			if (key.equals("cmd"))
				continue;
			params.put((String)key, ((String[])settings.get(key))[0]);
			sqlQuery += "UPDATE configuration SET value = ? WHERE name = ?;\n";
		}
		sqlQuery += "COMMIT;";

		// Execute SQL query.
		PreparedStatement pst = null;
		try
		{
			pst = _connection.prepareStatement(sqlQuery);

			// Set query parameters.
			int i = 1;
			for (String key : params.keySet())
			{
				pst.setString(i++, params.get(key));
				pst.setString(i++, key);
			}

			return pst.execute();
		}
		finally
		{
			if (pst != null)
				pst.close();
		}
	}

	public String getSetting(String name)
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;

		try
		{
			pst = _connection.prepareStatement("SELECT value FROM configuration WHERE name = ?");
			pst.setString(1, name);

			if (pst.execute())
			{
				rs = pst.getResultSet();
				if (rs.next())
					return rs.getString(1);
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			try
			{
				if (rs != null)
					rs.close();
				if (pst != null)
					pst.close();
			}
			catch (SQLException e)
			{
				e.printStackTrace();
			}
		}
		return null;
	}

	/**************************************************************************
	 *  Lookup maps.
	 */

	public String createLookup(InputStream inputStream) throws SaxonApiException, SQLException, IOException, ValidationException
	{
		return processGenericXmlCommand(inputStream, "xsd/lookup.xsd", "xslt/import_lookup_postgresql.xslt");
	}
	
	public boolean deleteLookup(String ns) throws SQLException
	{
		PreparedStatement pst = null;
		try
		{
			pst = _connection.prepareStatement("DELETE FROM lookup_ns WHERE ns = ?;");
			pst.setString(1, ns);
			return pst.execute();
		}
		finally
		{
			if (pst != null)
				pst.close();
		}
	}

	public LookupMapDescriptor getLookupMapType(String ns)
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;

		try
		{
			pst = _connection.prepareStatement("SELECT type, behaviour_type, behaviour_value FROM lookup_ns WHERE ns = ?");
			pst.setString(1, ns);

			if (pst.execute())
			{
				rs = pst.getResultSet();
				if (rs.next())
					return new LookupMapDescriptor(rs.getString(1), rs.getString(2), rs.getString(3));
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			try
			{
				if (rs != null)
					rs.close();
				if (pst != null)
					pst.close();
			}
			catch (SQLException e)
			{
				e.printStackTrace();
			}
		}
		return null;
	}

	public String getLookupValue(String ns, String key)
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;

		try
		{
			pst = _connection.prepareStatement("SELECT value FROM lookup WHERE ns = ? AND key = ? LIMIT 1");
			pst.setString(1, ns);
			pst.setString(2, key);

			if (pst.execute())
			{
				rs = pst.getResultSet();
				if (rs.next())
					return rs.getString(1);
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			try
			{
				if (rs != null)
					rs.close();
				if (pst != null)
					pst.close();
			}
			catch (SQLException e)
			{
				e.printStackTrace();
			}
		}
		return null;
	}

	protected String[] getLookupKeyValue(String ns)
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;

		try
		{
			pst = _connection.prepareStatement("SELECT key, value FROM lookup WHERE ns = ? LIMIT 1");
			pst.setString(1, ns);

			if (pst.execute())
			{
				rs = pst.getResultSet();
				if (rs.next())
					return new String[] { rs.getString(1), rs.getString(2) };
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			try
			{
				if (rs != null)
					rs.close();
				if (pst != null)
					pst.close();
			}
			catch (SQLException e)
			{
				e.printStackTrace();
			}
		}
		return null;
	}

	public String resolveLookupValue(String ns, String key)
	{
		LookupMapDescriptor lookupDescriptor = getLookupMapType(ns);
		if (lookupDescriptor == null)				
			return null;

		try
		{
			if (lookupDescriptor.isStatic())
			{
				String ret = getLookupValue(ns, key);
				return ret == null ? lookupDescriptor.getDefaultValue(key) : ret;
			}
			else if (lookupDescriptor.isHttpResolver())
			{
				final Pattern		reType = Pattern.compile("^T:(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
				final Pattern		reExtract = Pattern.compile("^E:(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
				final Pattern		reNamespace = Pattern.compile("^NS:(.+?):(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
				Matcher				m;
				String				endpoint, extractorType, extractor, content;
				String[]			dynKeyValue = getLookupKeyValue(ns);
	
				if (dynKeyValue == null)
					return null;
	
				// Endpoint.
				endpoint = dynKeyValue[0];
				if (endpoint.contains("$0"))
					endpoint = endpoint.replace("$0", key);
				else
					endpoint += key;
	
				// Type.
				m = reType.matcher(dynKeyValue[1]);
				m.find();
				extractorType = m.group(1);
	
				// Extractor.
				m = reExtract.matcher(dynKeyValue[1]);
				m.find();
				extractor = m.group(1);
	
				// Execute HTTP GET request.
				content = Http.simpleGetRequest(endpoint);
	
				// Retrieve data.
				if (extractor.equals(""))
					return content;
				if (extractorType.equalsIgnoreCase("Regex"))
				{
					Pattern re = Pattern.compile(extractor);
					m = re.matcher(content);
	
					if (m.find())
						return m.groupCount() > 0 ? m.group(1) : m.group();
				}
				else if (extractorType.equalsIgnoreCase("XPath"))
				{
					Processor			processor = new Processor(false);
					XPathCompiler		xpathCompiler = processor.newXPathCompiler();

					// Declare XML namespaces.
					m = reNamespace.matcher(dynKeyValue[1]);
					while (m.find())
						xpathCompiler.declareNamespace(m.group(1), m.group(2));

					// Evaluate XPath expression.
					XdmItem node = xpathCompiler.evaluateSingle(extractor, processor.newDocumentBuilder().build(new StreamSource(new StringReader(content))));
					return node == null ? lookupDescriptor.getDefaultValue(key) : node.getStringValue();
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		return lookupDescriptor.getDefaultValue(key);
	}

	public String exportLookup(String ns) throws SQLException
	{
		PreparedStatement	pst = null;
		ResultSet			rs = null;
		String				ret = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<lookup xmlns=\"urn:csiro:xmlns:pidsvc:lookup:1.0\">";

		try
		{
			pst = _connection.prepareStatement("SELECT type, behaviour_type, behaviour_value FROM lookup_ns WHERE ns = ?;SELECT key, value FROM lookup WHERE ns = ?;");
			pst.setString(1, ns);
			pst.setString(2, ns);

			if (pst.execute())
			{
				rs = pst.getResultSet();
				if (!rs.next())
					throw new SQLException("Lookup map configuration cannot be exported. Data is corrupted.");

				String lookupType = rs.getString("type");
				ret += "<ns>" + StringEscapeUtils.escapeXml(ns) + "</ns>";

				String behaviourValue = rs.getString("behaviour_value");
				ret += "<default type=\"" + StringEscapeUtils.escapeXml(rs.getString("behaviour_type")) + "\">" + (behaviourValue == null ? "" : StringEscapeUtils.escapeXml(behaviourValue)) + "</default>";

				pst.getMoreResults();
				rs = pst.getResultSet();
				if (lookupType.equalsIgnoreCase("Static"))
				{
					ret += "<Static>";
					while (rs.next())
					{
						ret += "<pair>";
						ret += "<key>" + StringEscapeUtils.escapeXml(rs.getString(1)) + "</key>";
						ret += "<value>" + StringEscapeUtils.escapeXml(rs.getString(2)) + "</value>";
						ret += "</pair>";
					}
					ret += "</Static>";
				}
				else if (lookupType.equalsIgnoreCase("HttpResolver"))
				{
					ret += "<HttpResolver>";
					if (!rs.next())
						throw new SQLException("Lookup map configuration cannot be exported. Data is corrupted.");

					final Pattern		reType = Pattern.compile("^T:(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
					final Pattern		reExtract = Pattern.compile("^E:(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
					final Pattern		reNamespace = Pattern.compile("^NS:(.+?):(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
					Matcher				m;
					String				namespaces = "";
					String				buf = rs.getString(2);

					ret += "<endpoint>" + StringEscapeUtils.escapeXml(rs.getString(1)) + "</endpoint>";

					// Type.
					m = reType.matcher(buf);
					m.find();
					ret += "<type>" + m.group(1) + "</type>";

					// Extractor.
					m = reExtract.matcher(buf);
					m.find();
					ret += "<extractor>" + StringEscapeUtils.escapeXml(m.group(1)) + "</extractor>";

					// Namespaces.
					m = reNamespace.matcher(buf);
					while (m.find())
						namespaces += "<ns prefix=\"" + StringEscapeUtils.escapeXml(m.group(1)) + "\">" + StringEscapeUtils.escapeXml(m.group(2)) + "</ns>";
					if (!namespaces.isEmpty())
						ret += "<namespaces>" + namespaces + "</namespaces>";

					ret += "</HttpResolver>";
				}
				ret += "</lookup>";
			}
		}
		finally
		{
			if (rs != null)
				rs.close();
			if (pst != null)
				pst.close();
		}
		return ret;
	}

	public String importLookup(HttpServletRequest request)
	{
		return unwrapCompressedBackupFile(request, new ICallback() {
			@Override
			public String process(InputStream inputStream) throws Exception
			{
				return createLookup(inputStream);
			}
		});
	}
}
