package csiro.pidsvc.mappingstore.condition;

import javax.servlet.http.HttpServletRequest;

import csiro.pidsvc.helper.URI;

public class ConditionComparator extends AbstractConditionComparator
{
	public ConditionComparator(URI uri, HttpServletRequest request, int id, String match, Object matchAuxiliaryData)
	{
		super(uri, request, id, match, matchAuxiliaryData);
	}

	@Override
	public boolean compare(String arg0, String arg1)
	{
		return arg0.equals(arg1);
	}
}