package org.ragtime.radio

import org.junit.Assert.*
import org.junit.Test

/** The demo guide's steps, each from nothing but what the screen shows. */
class DemoGuideTest {
    private val stations=listOf(StationRow("W1QSO","FN42","● QRV","just now","+12 dB"),StationRow("K7QTH","CN87","● QRV","just now","+3 dB"),
        StationRow("F5QRN","JN18","○ No sessions","just now","-11 dB"))
    private fun line(id:Long,text:String,ours:Boolean,to:String="",state:String="Sent",kind:Int=0)=ChatRow(id,if(ours)"N0DEMO" else "W1QSO",text,to,ours,0,state,kind)
    private fun file(via:String,name:String,state:String,ours:Boolean=false,receive:Boolean=false,checked:Boolean=false)=
        TransferRow("x:$name",via,name,"K7QTH",state,"",100,0,0,ours,receive,checked,receive,receive,false,false,false,"")
    private val net=RadioSnapshot(ready=true,dataUp=true,stations=stations)

    @Test fun walksTheTourInOrder() {
        assertEquals("Starting the demo net…",demoTip(RadioSnapshot(),"N0DEMO").text)
        assertTrue(demoTip(RadioSnapshot(ready=true),"N0DEMO").text.startsWith("Listening"))

        val hello=demoTip(net,"N0DEMO")
        assertEquals("CQ D2G de N0DEMO, how copy?",hello.suggestion)
        assertEquals("Broadcast",hello.thread)

        val spoke=net.copy(broadcast=listOf(line(1,"CQ D2G de N0DEMO, how copy?",true)))
        val address=demoTip(spoke,"N0DEMO")
        assertEquals("@W1QSO good evening from N0DEMO",address.suggestion)
        assertEquals("Broadcast",address.thread)

        val addressed=spoke.copy(broadcast=spoke.broadcast+line(2,"good evening from N0DEMO",true,"W1QSO","Acknowledged"))
        val call=demoTip(addressed,"N0DEMO")
        assertTrue(call.text,call.text.contains("tap K7QTH, then Call K7QTH"))
        assertEquals("",call.suggestion)

        assertTrue(demoTip(addressed.copy(active=true,phase=1,peer="K7QTH"),"N0DEMO").text.startsWith("Calling K7QTH"))

        val connected=addressed.copy(active=true,phase=2,peer="K7QTH",session=listOf(line(10,"Hello N0DEMO",false)))
        val first=demoTip(connected,"N0DEMO")
        assertEquals("Good copy here. What's your rig?",first.suggestion)
        assertEquals("Ragchew",first.thread)

        val chatting=connected.copy(session=connected.session+line(11,"Good copy here. What's your rig?",true))
        assertEquals("Can you send me your bulletin file?",demoTip(chatting,"N0DEMO").suggestion)

        val asked=chatting.copy(session=chatting.session+line(12,"Can you send me your bulletin file?",true))
        assertEquals("",demoTip(asked,"N0DEMO").suggestion)
        val offered=asked.copy(files=listOf(file("Session","K7QTH-bulletin.txt","Offered",receive=true)))
        assertTrue(demoTip(offered,"N0DEMO").text.contains("Open Files and tap Receive"))

        val saved=asked.copy(files=listOf(file("Session","K7QTH-bulletin.txt","Saved",checked=true)))
        assertEquals("Thanks for the ragchew, 73",demoTip(saved,"N0DEMO").suggestion)
        val signedOff=saved.copy(session=saved.session+line(13,"Thanks for the ragchew, 73",true))
        assertTrue(demoTip(signedOff,"N0DEMO").text.contains("signing off"))

        val after=signedOff.copy(active=false,phase=0)
        assertTrue(demoTip(after,"N0DEMO").text.startsWith("That's the tour"))
        val bulletin=after.copy(files=after.files+file("Broadcast","W1QSO-net-news-1.txt","Heard",receive=true))
        assertTrue(demoTip(bulletin,"N0DEMO").text.contains("is broadcasting W1QSO-net-news-1.txt"))
    }

    @Test fun neverSuggestsCallingAStationThatTakesNoSessions() {
        val only=net.copy(stations=listOf(stations[2],stations[0]),
            broadcast=listOf(line(1,"CQ",true),line(2,"hi",true,"W1QSO")))
        assertTrue(demoTip(only,"N0DEMO").text.contains("Call W1QSO"))
    }

    @Test fun privacyTextIsReadAsHeadingsParagraphsAndBullets() {
        val parts=noticeParts("# Title\n\nEffective today.\n\n## Short\n\n- One\n- Two that\n  is not continued\n\nA paragraph\nover two lines.\n")
        assertEquals(listOf(NoticePart.Kind.Title,NoticePart.Kind.Paragraph,NoticePart.Kind.Heading,NoticePart.Kind.Bullet,NoticePart.Kind.Bullet,
            NoticePart.Kind.Paragraph,NoticePart.Kind.Paragraph),parts.map { it.kind })
        assertEquals("A paragraph over two lines.",parts.last().text)
        val licences=licenceParts("MIT License\n\nCopyright (c) 2026 Jeffrey Baitis\n","Apache License\n\nEND OF TERMS AND CONDITIONS\n")
        assertTrue(licences.any { it.kind==NoticePart.Kind.Legal&&it.text=="Copyright (c) 2026 Jeffrey Baitis" })
        assertTrue(licences.any { it.kind==NoticePart.Kind.Legal&&it.text=="END OF TERMS AND CONDITIONS" })
    }

    @Test fun airtimeIsSaidInSecondsThenMinutes() {
        assertEquals("1 s",GearState.duration(0.2))
        assertEquals("59 s",GearState.duration(59.4))
        assertEquals("1 min 10 s",GearState.duration(70.0))
        assertEquals("12 min",GearState.duration(700.0))
        assertEquals("about 2 min 8 s",GearState(bps=500).airtime(8000))
        assertEquals("No gear, no estimate","",GearState().airtime(8000))
        assertEquals(GearState(),GearState.parse(null))
    }

    @Test fun demoSettingsNeedNoHostOrCallsign() {
        val demo=HostSettings(host="",call="",demo=true).validated()
        assertEquals(HostSettings.DEMO_CALL,demo.call)
        assertEquals("N0DEMO-1",HostSettings(host="",call="n0demo-1",demo=true).validated().call)
        assertThrows(IllegalArgumentException::class.java) { HostSettings(host="",call="N0DEMO",demo=false).validated() }
        assertThrows(IllegalArgumentException::class.java) { HostSettings(host="",call="not a call!",demo=true).validated() }
    }
}
