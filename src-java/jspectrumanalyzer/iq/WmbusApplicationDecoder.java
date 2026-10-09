package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Wireless M-Bus application decoder. Manufacturer drivers add semantics to
 * the common EN 13757/OMS DIF/VIF record decoder; encrypted content is never
 * guessed without a key.
 */
final class WmbusApplicationDecoder {
	private WmbusApplicationDecoder() { }

	static Result decode(byte[] telegram,String manufacturer,int version,int type,int ci){
		if("TCH".equals(manufacturer)&&(version==0x69||version==0x94)&&type==0x80
				&&(ci==0xa0||ci==0xa2))return decodeFhkvDataIII(telegram);
		String driver=driver(manufacturer,version,type),medium=medium(type);
		if("apatoreitn".equals(driver)){
			Result apator=decodeApatoreitn(telegram);
			if(apator!=Result.NONE)return apator;
		}
		int dataStart=clearDataStart(telegram,ci);
		if(dataStart<0)return Result.identified(driver,medium);
		if(driver.length()==0)driver="oms";
		if("qcaloric".equals(driver)){
			Result walkBy=decodeQcaloricWalkBy(telegram,dataStart);
			if(walkBy!=Result.NONE)return walkBy;
		}
		DecodedRecords records=decodeRecords(telegram,dataStart);
		if(!records.hasValues())return Result.identified(driver,medium);
		return records.result(driver,medium);
	}

	/** Techem FHKV data II/III, equivalent to the wmbusmeters fhkvdataiii layout. */
	private static Result decodeFhkvDataIII(byte[] telegram){
		final int at=11;
		if(telegram.length<=at)return Result.NONE;
		int marker=telegram[at]&255;
		int roomOffset,minimum;
		if(marker==0x01||marker==0x11){roomOffset=at+9;minimum=at+13;}
		else if(marker==0x0f){roomOffset=at+10;minimum=at+14;}
		else return Result.fhkvDataIII();
		if(telegram.length<minimum)return Result.fhkvDataIII();

		int previousDate=u16le(telegram,at+1),previousHca=u16le(telegram,at+3);
		int currentDate=u16le(telegram,at+5),currentHca=u16le(telegram,at+7);
		int room=s16le(telegram,roomOffset),radiator=s16le(telegram,roomOffset+2);
		int previousDay=previousDate&31,previousMonth=(previousDate>>5)&15;
		int previousYear=2000+(previousDate>>9);
		int currentDay=(currentDate>>4)&31;if(currentDay==0)currentDay=1;
		int currentMonth=(currentDate>>9)&15;if(currentMonth==0)currentMonth=12;
		int currentYear=previousYear+(currentMonth<previousMonth
				||(currentMonth==previousMonth&&currentDay<=previousDay)?1:0);
		return new Result("fhkvdataiii","heat cost allocator","clear (manufacturer-specific)",
				currentHca+" HCA",date(currentYear,currentMonth,currentDay),previousHca+" HCA",
				date(previousYear,previousMonth,previousDay),decimal(room/100d)+" C",
				decimal(radiator/100d)+" C","");
	}

	/** Qundis/Siemens Type-2 WalkByDataSet carried in a 0D FF 5F record. */
	private static Result decodeQcaloricWalkBy(byte[] telegram,int at){
		if(at+5>telegram.length||(telegram[at]&255)!=0x0d||(telegram[at+1]&255)!=0xff
				||(telegram[at+2]&255)!=0x5f)return Result.NONE;
		int length=telegram[at+3]&255,body=at+4,end=body+length;
		if(length<28||end>telegram.length||(telegram[body+9]&255)!=0x6e)return Result.NONE;
		long current=bcd(telegram,body+12,4),previous=bcd(telegram,body+18,4);
		long last=bcd(telegram,body+24,4);String previousDate=mbusDate(telegram,body+16);
		String lastDate=mbusDate(telegram,body+22),currentDate="";
		DecodedRecords tail=decodeRecords(telegram,end);if(tail.currentDate.length()>0)currentDate=tail.currentDate;
		List<String> extra=new ArrayList<String>();
		String previousValues=previous<0?"":previous+" HCA",previousDates=previousDate;
		if(last>=0||lastDate.length()>0){
			previousValues=appendLine(previousValues,last<0?"--":last+" HCA");
			previousDates=appendLine(previousDates,lastDate.length()==0?"--":lastDate);
		}
		String errorDate=mbusDate(telegram,body+10);if(errorDate.length()>0)extra.add("error_date="+errorDate);
		return new Result("qcaloric","heat cost allocator","",
				current<0?"":current+" HCA",currentDate,previousValues,
				previousDates,"","",join(extra));
	}

	/** Apator E-ITN 30.51 proprietary clear payload, matching the
	 * wmbusmeters apatoreitn frame_direct/frame_b6 layouts. */
	private static Result decodeApatoreitn(byte[] telegram){
		if(telegram==null||telegram.length<26)return Result.NONE;
		int at=11;
		if((telegram[at]&255)==0xa0)at++;
		else if((telegram[at]&255)<=0x0f){
			int marker=at+1+(telegram[at]&255);
			if(marker>=telegram.length||(telegram[marker]&255)!=0xa0)return Result.NONE;
			at=marker+1;
		}
		if(at+15>telegram.length)return Result.NONE;
		int season=(telegram[at]&255)|0xa000;
		int previous=u16le(telegram,at+3),esb=u16le(telegram,at+5);
		int current=u16le(telegram,at+7),currentDate=u16le(telegram,at+9);
		double previousRoom=fixed88(telegram,at+11),room=fixed88(telegram,at+13);
		List<String> extra=new ArrayList<String>();
		String esbDate=apatorDate(esb);if(esb!=0&&esbDate.length()>0)extra.add("esb_date="+esbDate);
		extra.add("temp_room_previous="+decimal(previousRoom)+" C");
		return new Result("apatoreitn","heat cost allocator","clear (manufacturer-specific)",
				current+" HCA",apatorDate(currentDate),previous+" HCA",apatorDate(season),
				decimal(room)+" C","",join(extra));
	}

	private static int clearDataStart(byte[] telegram,int ci){
		return telegram==null||telegram.length<=10?-1:clearDataStart(telegram,10,0);
	}

	private static int clearDataStart(byte[] telegram,int ciAt,int depth){
		if(depth>3||ciAt<0||ciAt>=telegram.length)return-1;
		int ci=telegram[ciAt]&255,ell=ellLength(ci);
		if(ell>=0){
			if(ciAt+1+ell>=telegram.length)return-1;
			if(ci==0x8d||ci==0x8f){int snAt=ciAt+(ci==0x8f?11:3);
				if(snAt+3>=telegram.length||((unsignedLe(telegram,snAt,4)>>>29)&7)!=0)return-1;}
			return clearDataStart(telegram,ciAt+1+ell,depth+1);
		}
		if(ci==0x90){if(ciAt+1>=telegram.length)return-1;int length=telegram[ciAt+1]&255;
			return clearDataStart(telegram,ciAt+2+length,depth+1);}
		if(ci==0x78)return ciAt+1<telegram.length?ciAt+1:-1;
		if(ci==0x7a){int config=ciAt+3;if(ciAt+5>telegram.length||securityMode(telegram,config)!=0)return-1;
			return ciAt+5;}
		if(ci==0x72){int config=ciAt+11;if(ciAt+13>telegram.length||securityMode(telegram,config)!=0)return-1;
			return ciAt+13;}
		return-1;
	}

	private static int ellLength(int ci){switch(ci){case 0x8c:return 2;case 0x8d:return 8;
		case 0x8e:return 10;case 0x8f:return 16;default:return-1;}}

	private static int securityMode(byte[] bytes,int at){
		if(at+1>=bytes.length)return-1;int config=(bytes[at]&255)|((bytes[at+1]&255)<<8);
		return(config>>8)&31;
	}

	private static String driver(String manufacturer,int version,int type){
		if(("QDS".equals(manufacturer)||"LSE".equals(manufacturer))&&type==0x08)return"qcaloric";
		if("LSE".equals(manufacturer)&&(type==0x06||type==0x07))return"lse_07_17";
		if("KAM".equals(manufacturer)&&(type==0x04||type==0x0a||type==0x0b
				||type==0x0c||type==0x0d))return"kamheat";
		if("IST".equals(manufacturer)&&version==0xa9&&type==0x04)return"istaheat";
		if("IST".equals(manufacturer)&&type==0x08)return"ista-hca";
		if(("APA".equals(manufacturer)||"APT".equals(manufacturer))&&version==0x04&&type==0x08)
			return"apatoreitn";
		return"";
	}

	private static String medium(int type){switch(type){
		case 0x04:case 0x0c:return"heat meter";case 0x06:return"warm-water meter";
		case 0x07:return"water meter";case 0x08:return"heat cost allocator";
		case 0x0a:case 0x0b:return"cooling meter";case 0x0d:return"heat/cooling meter";
		default:return"";}}

	private static DecodedRecords decodeRecords(byte[] bytes,int start){
		DecodedRecords out=new DecodedRecords();int at=Math.max(0,start);
		while(at<bytes.length){int dif=bytes[at++]&255;
			if(dif==0x2f)continue;if((dif&15)==15)break;
			int storage=(dif>>6)&1,shift=1;
			if((dif&0x80)!=0){int extensions=0,dife;
				do{if(at>=bytes.length||extensions++>=10)return out;dife=bytes[at++]&255;
					storage|=(dife&15)<<shift;shift+=4;}while((dife&0x80)!=0);}
			if(at>=bytes.length)return out;int rawVif=bytes[at++]&255,vif=rawVif&0x7f;
			int vife=-1;if((rawVif&0x80)!=0){int extensions=0,next;
				do{if(at>=bytes.length||extensions++>=10)return out;next=bytes[at++]&255;
					if(vife<0)vife=next&0x7f;}while((next&0x80)!=0);}
			int dataType=dif&15,length=dataLength(dataType);
			if(dataType==13){if(at>=bytes.length)return out;int variable=bytes[at++]&255;
				int skip=variable<=0xbf?variable:variable>=0xe0&&variable<=0xef?variable-0xe0:-1;
				if(skip<0||at+skip>bytes.length)return out;at+=skip;continue;}
			if(length<0||at+length>bytes.length)return out;
			int function=(dif>>4)&3;
			if(vif==0x6c&&length>=2){out.date(storage,function,mbusDate(bytes,at));out.any=true;}
			else if(vif==0x6d&&length>=4){out.currentDate=mbusDateTime(bytes,at);out.any=true;}
			else{Double value=numeric(bytes,at,length,dataType);
				if(value!=null)out.value(vif,vife,storage,function,value.doubleValue());}
			at+=length;
		}
		return out;
	}

	private static int dataLength(int type){switch(type){case 0:case 8:return 0;case 1:case 9:return 1;
		case 2:case 10:return 2;case 3:case 11:return 3;case 4:case 5:case 12:return 4;
		case 6:case 14:return 6;case 7:return 8;default:return-1;}}

	private static Double numeric(byte[] bytes,int at,int length,int type){
		if(length==0)return null;
		if(type>=9&&type<=14){long value=bcd(bytes,at,length);return value<0?null:Double.valueOf(value);}
		if(type==5&&length==4){int bits=(int)unsignedLe(bytes,at,4);return Double.valueOf(Float.intBitsToFloat(bits));}
		long value=unsignedLe(bytes,at,length);if(length<8&&(bytes[at+length-1]&0x80)!=0)value|=(-1L)<<(length*8);
		return Double.valueOf(value);
	}

	private static long unsignedLe(byte[] bytes,int at,int length){long value=0;
		for(int n=0;n<length;n++)value|=(long)(bytes[at+n]&255)<<(n*8);return value;}
	private static long bcd(byte[] bytes,int at,int length){long value=0,multiplier=1;
		if(at<0||at+length>bytes.length)return-1;
		for(int n=0;n<length;n++){int b=bytes[at+n]&255,lo=b&15,hi=b>>4;if(lo>9||hi>9)return-1;
			value+=(lo+hi*10L)*multiplier;multiplier*=100L;}return value;}

	private static String mbusDate(byte[] bytes,int at){if(at+1>=bytes.length)return"";
		int value=u16le(bytes,at),day=value&31,month=(value>>8)&15;
		int year=2000+((value>>5)&7)+(((value>>12)&15)<<3);
		return day<1||day>31||month<1||month>12?"":date(year,month,day);}
	private static String mbusDateTime(byte[] bytes,int at){if(at+3>=bytes.length)return"";
		int minute=bytes[at]&0x3f,hour=bytes[at+1]&0x1f;String day=mbusDate(bytes,at+2);
		return day.length()==0||minute>59||hour>23?"":String.format(Locale.US,"%s %02d:%02d",day,hour,minute);}

	private static int u16le(byte[] bytes,int at){return(bytes[at]&255)|((bytes[at+1]&255)<<8);}
	private static int s16le(byte[] bytes,int at){return(short)u16le(bytes,at);}
	private static double fixed88(byte[] bytes,int at){int raw=u16le(bytes,at);return(raw>>>8)+(raw&255)/256d;}
	private static String apatorDate(int value){int day=value&31,month=(value>>5)&15,year=2000+((value>>9)&31);
		return day<1||day>31||month<1||month>12?"":date(year,month,day);}
	private static String date(int year,int month,int day){return String.format(Locale.US,
			"%04d-%02d-%02d",year,month,day);}
	private static String decimal(double value){return String.format(Locale.US,"%.2f",value);}
	private static String number(double value){if(Math.abs(value-Math.rint(value))<1e-9)return Long.toString(Math.round(value));
		String text=String.format(Locale.US,"%.6f",value);int end=text.length();
		while(end>0&&text.charAt(end-1)=='0')end--;return text.substring(0,end);}
	private static String join(List<String> values){StringBuilder out=new StringBuilder();
		for(String value:values){if(value==null||value.length()==0)continue;if(out.length()>0)out.append("; ");out.append(value);}return out.toString();}
	private static String appendLine(String values,String value){
		if(value==null||value.length()==0)return values;
		return values==null||values.length()==0?value:values+'\n'+value;
	}

	private static final class DecodedRecords{
		String current="",currentDate="",room="",radiator="";
		final Map<Integer,Historical> history=new TreeMap<Integer,Historical>();
		final List<String> extra=new ArrayList<String>();boolean any;
		boolean hasValues(){return any;}
		void date(int storage,int function,String value){if(value.length()==0)return;
			if(function==3){extra.add("error_date="+value);return;}
			if(storage>0)historical(storage).date=value;
			else if(currentDate.length()==0)currentDate=value;}
		void value(int vif,int vife,int storage,int function,double raw){
			String value,label="";any=true;
			if(vif<=0x07){value=number(raw*Math.pow(10d,(vif&7)-6))+" kWh";label="energy";}
			else if(vif>=0x08&&vif<=0x0f){value=number(raw*Math.pow(10d,vif&7))+" J";label="energy";}
			else if(vif>=0x10&&vif<=0x17){value=number(raw*Math.pow(10d,(vif&7)-6))+" m3";label="volume";}
			else if(vif>=0x28&&vif<=0x2f){value=number(raw*Math.pow(10d,(vif&7)-3))+" W";label="power";}
			else if(vif>=0x38&&vif<=0x3f){value=number(raw*Math.pow(10d,(vif&7)-6))+" m3/h";label="flow";}
			else if(vif>=0x58&&vif<=0x5b){value=number(raw*Math.pow(10d,(vif&3)-3))+" C";label="flow_temp";
				if(storage==0&&radiator.length()==0)radiator=value;else extra.add(label+'='+value);return;}
			else if(vif>=0x5c&&vif<=0x5f){value=number(raw*Math.pow(10d,(vif&3)-3))+" C";label="return_temp";}
			else if(vif>=0x64&&vif<=0x67){value=number(raw*Math.pow(10d,(vif&3)-3))+" C";label="external_temp";
				if(storage==0&&room.length()==0)room=value;else extra.add(label+'='+value);return;}
			else if(vif==0x6e){value=number(raw)+" HCA";label="hca";}
			else{extra.add(String.format(Locale.US,"VIF_0x%02X%s=%s",vif,
					vife<0?"":String.format(Locale.US,"_0x%02X",vife),number(raw)));return;}
			if(function==3){extra.add("error_"+label+'='+value);return;}
			if(storage==0&&current.length()==0)current=value;
			else if(storage>0&&historical(storage).value.length()==0)historical(storage).value=value;
			else extra.add(label+'_'+storage+'='+value);
		}
		private Historical historical(int storage){Historical value=history.get(Integer.valueOf(storage));
			if(value==null){value=new Historical();history.put(Integer.valueOf(storage),value);}return value;}
		Result result(String driver,String medium){String previous="",previousDate="";
			for(Historical value:history.values()){
				if(value.value.length()==0&&value.date.length()==0)continue;
				previous=appendLine(previous,value.value.length()==0?"--":value.value);
				previousDate=appendLine(previousDate,value.date.length()==0?"--":value.date);
			}
			return new Result(driver,medium,"",current,currentDate,
					previous,previousDate,room,radiator,join(extra));}
	}
	private static final class Historical{String value="",date="";}

	static final class Result {
		static final Result NONE=new Result("","","","","","","","","","");
		final String driver,medium,security,currentReading,currentDate,previousReading;
		final String previousDate,roomTemperature,radiatorTemperature,unparsedValues;
		Result(String driver,String medium,String security,String currentReading,String currentDate,
				String previousReading,String previousDate,String roomTemperature,
				String radiatorTemperature,String unparsedValues){
			this.driver=driver;this.medium=medium;this.security=security;
			this.currentReading=currentReading;this.currentDate=currentDate;
			this.previousReading=previousReading;this.previousDate=previousDate;
			this.roomTemperature=roomTemperature;this.radiatorTemperature=radiatorTemperature;
			this.unparsedValues=unparsedValues;
		}
		static Result fhkvDataIII(){return new Result("fhkvdataiii","heat cost allocator",
				"clear (manufacturer-specific)","","","","","","","");}
		static Result identified(String driver,String medium){return driver.length()==0?NONE:
				new Result(driver,medium,"","","","","","","","");}
		String summary(){
			StringBuilder out=new StringBuilder();
			append(out,"current",currentReading);append(out,"current_date",currentDate);
			append(out,"previous",previousReading);append(out,"previous_date",previousDate);
			append(out,"temp_room",roomTemperature);append(out,"temp_radiator",radiatorTemperature);
			append(out,"other",unparsedValues);return out.toString();
		}
		private static void append(StringBuilder out,String key,String value){
			if(value.length()==0)return;if(out.length()>0)out.append("; ");
			out.append(key).append('=').append(value.replace("\n"," / "));
		}
	}
}
