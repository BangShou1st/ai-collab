import { chromium } from 'playwright';
const b = await chromium.launch();
const p = await b.newPage({viewport:{width:1440,height:900}});
await p.goto('http://localhost:5173/login',{waitUntil:'networkidle'});
await p.waitForTimeout(1500);
await p.screenshot({path:'E:/project/ai-collab/docs/superpowers/audits/product-experience-v2/before/01-login.png'});
await p.goto('http://localhost:5173/register',{waitUntil:'networkidle'});
await p.waitForTimeout(1500);
await p.screenshot({path:'E:/project/ai-collab/docs/superpowers/audits/product-experience-v2/before/02-register.png'});
await b.close();
console.log('done');

