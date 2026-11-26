// Offline, deterministic fixture preparation; never used to calculate production recommendations.
const fs=require('fs'),crypto=require('crypto'),path=require('path');
const folder=__dirname,catalog=JSON.parse(fs.readFileSync(path.join(folder,'demo-catalog.json')));
const uuid=key=>{const h=crypto.createHash('sha256').update(key).digest('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-4${h.slice(13,16)}-a${h.slice(17,20)}-${h.slice(20,32)}`;};
const inventory={products:catalog.entries.map(e=>e.product),batches:[],movements:[],readings:[],receipts:{},sensorInputs:[],baselines:{},dataset:"demo"};
catalog.entries.forEach((entry,i)=>{
 const batchId=i===0?'LOT-C':'DEMO-'+entry.product.id;let quantity=40;
 inventory.baselines[batchId]={quantity,recordedAt:'2026-05-01T00:00:00Z'};
 const move=(delta,kind,date,reason)=>{quantity+=delta;const id=uuid(batchId+date+kind);inventory.movements.push({id,batchId,delta,quantityAfter:quantity,occurredAt:date,eventId:uuid(id),productId:entry.product.id,kind,source:'demo-fixture',reason});};
 for(let month=5;month<=11;month++)for(const day of [3,10,17,24]){
  if(month===11&&day>19)continue;
  const date=`2026-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}T12:00:00Z`;
  const units=3+(i%4)+(month-5)%2;
  if(quantity<units+10)move(40,'replenishment',date.replace('12:','08:'),'Livraison fictive');
  move(-units,'consumption',date,'Consommation fictive, aucun conseil de traitement');
 }
 const target=i%2===0?6:50;
 move(target-quantity,'correction','2026-11-19T16:00:00Z','Inventaire fictif ; exclu de la consommation');
 inventory.batches.push({id:batchId,productId:entry.product.id,location:'cabinet',quantity,expiresOn:i===6?'2026-11-25':'2027-12-31'});
});
for(let month=5;month<=11;month++)for(let day=1;day<=new Date(Date.UTC(2026,month,0)).getUTCDate();day++){
 if(month===11&&day>19)continue;
 if(day===12||day===13)continue; // Explicit gaps, not zero-valued readings.
 for(const hour of [8,16])for(const [kind,value] of [['temperature.measured',20+((day+hour)%7)/10],['humidity.measured',45+(day%9)]])inventory.readings.push({kind,value,source:'demo-sensor',observedAt:`2026-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}T${String(hour).padStart(2,'0')}:00:00Z`});
}
fs.writeFileSync(path.join(folder,'demo-history.json'),JSON.stringify({exampleData:true,referenceTime:'2026-11-20T12:00:00Z',inventory},null,2)+'\n');
console.log('Deterministic demo fixture generated.');
