package spim.fiji.datasetmanager.patterndetector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import spim.fiji.datasetmanager.patterndetector.FilenamePatternDetector;

public class NumericalFilenamePatternDetector implements FilenamePatternDetector
{
	private List<String> invariants;
	private List<List<String>> variables;
	@Override
	public void detectPatterns(List< File > files)
	{
		Pair< List< String >, List< List< String > > > res = detectNumericPatterns( files.stream().map( File::getAbsolutePath ).collect( Collectors.toList() ) );
		invariants = res.getA();
		variables = res.getB();
	}
	
	@Override
	public String getInvariant(int n){ return invariants.get( n );}
	@Override
	public List< String > getValuesForVariable(int n){return variables.get( n );}
	@Override
	public Pattern getPatternAsRegex()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < invariants.size() - 1; i++)
			sb.append( invariants.get( i ) + "(\\d+)" );
		sb.append( invariants.get( invariants.size()-1 ) );
		return Pattern.compile( sb.toString() );
	}

	@Override
	public String getStringRepresentation()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < invariants.size() - 1; i++)
			sb.append( invariants.get( i ) + "{" + i + "}" );
		sb.append( invariants.get( invariants.size()-1 ) );
		return sb.toString();
	}
	
	public static String maxPrefix(Iterable<String> strings)
	{
		int maxIdx = 0;
		while (true)
		{
			Character currentChar = null;
			for (String s: strings)
			{
				
				if (maxIdx >= s.length())
					return s;
				else
				{
					if (currentChar == null)
						currentChar = s.charAt( maxIdx );
					if (!currentChar.equals( s.charAt( maxIdx ) ))
						return s.substring( 0, maxIdx );
				}
			}
			currentChar = null;
			maxIdx++;
		}
	}
	
	public static Pair<List<String>, List<String>> collectNumericPrefixes(Iterable<String> strings)
	{
		Pattern p = Pattern.compile( "(\\d+)(.+?)" );
		List<String> prefixes = new ArrayList<>();
		List<String> remainders = new ArrayList<>();
		for (String s : strings){
			Matcher m = p.matcher( s );
			if (! m.matches())
				return null;
			prefixes.add(m.group( 1 ));
			remainders.add( m.group( 2 ) );				
		}
		return new ValuePair< List<String>, List<String> >( prefixes, remainders );
	}
	
	public static Pair<List<String>, List<List<String>>> detectNumericPatterns(Iterable<String> strings)
	{
		
		List<String> tStrings = new ArrayList<>();
		strings.forEach( tStrings::add );
		List<String> invariants = new ArrayList<>();
		List<List<String>> variants = new ArrayList<>();
				
		while(true)
		{
			String prefix = maxPrefix( tStrings );
			
			//System.out.println( prefix );
			
			invariants.add( prefix );
			
			tStrings = tStrings.stream().map( s -> s.substring(prefix.length() ) ).collect( Collectors.toList() );
			Pair< List< String >, List< String > > collectNumericPrefixes = collectNumericPrefixes( tStrings );
			
			if (collectNumericPrefixes == null)
				break;
			
			//collectNumericPrefixes.getA().forEach( System.out::println );
			
			variants.add( collectNumericPrefixes.getA() );
			
			tStrings = collectNumericPrefixes.getB();			
		}
		
		return new ValuePair< List<String>, List<List<String>> >( invariants, variants );
		
	}

	@Override
	public int getNumVariables(){return variables.size();}
	
}